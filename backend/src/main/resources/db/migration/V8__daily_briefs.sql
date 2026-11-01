-- Phase 5 / Milestone 3: automated daily investment brief. brief_jobs is the persistent generation queue
-- (same pattern as automation_jobs); briefs are immutable revisions written when a job completes;
-- brief_sources records every source a brief selected, cited, listed as pending or omitted.

-- When a source first became searchable. Reindexing keeps it, so old research never reappears as new.
ALTER TABLE knowledge_sources ADD COLUMN first_indexed_at TIMESTAMPTZ;
UPDATE knowledge_sources SET first_indexed_at = completed_at WHERE status = 'INDEXED';
CREATE INDEX ix_knowledge_sources_first_indexed ON knowledge_sources (first_indexed_at) WHERE first_indexed_at IS NOT NULL;

CREATE TABLE brief_jobs (
    id                  UUID           PRIMARY KEY,
    trigger_type        VARCHAR(16)    NOT NULL,
    portfolio_id        UUID           REFERENCES portfolios (id) ON DELETE CASCADE,
    period_start        TIMESTAMPTZ    NOT NULL,
    period_end          TIMESTAMPTZ    NOT NULL,
    zone                VARCHAR(64)    NOT NULL,
    business_date       DATE           NOT NULL,
    partial_period      BOOLEAN        NOT NULL DEFAULT false,
    previous_brief_id   UUID,
    config_version      VARCHAR(32)    NOT NULL,
    idempotency_key     VARCHAR(200)   NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    outcome             VARCHAR(16),
    brief_id            UUID,
    attempts            INTEGER        NOT NULL DEFAULT 0,
    max_attempts        INTEGER        NOT NULL,
    next_attempt_at     TIMESTAMPTZ    NOT NULL,
    claimed_at          TIMESTAMPTZ,
    claim_token         UUID,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    error_code          VARCHAR(40),
    error_summary       VARCHAR(500),
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_brief_jobs_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'REGENERATION')),
    CONSTRAINT ck_brief_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_brief_jobs_outcome CHECK (outcome IN ('CREATED', 'UNCHANGED', 'SKIPPED')),
    CONSTRAINT ck_brief_jobs_period CHECK (period_end > period_start),
    CONSTRAINT ck_brief_jobs_attempts CHECK (attempts >= 0 AND max_attempts > 0),
    CONSTRAINT ck_brief_jobs_tokens CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0)),
    CONSTRAINT ck_brief_jobs_regeneration CHECK ((trigger_type = 'REGENERATION') = (previous_brief_id IS NOT NULL)),
    CONSTRAINT uk_brief_jobs_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX ix_brief_jobs_due ON brief_jobs (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_brief_jobs_running ON brief_jobs (claimed_at) WHERE status = 'RUNNING';

CREATE TABLE briefs (
    id                      UUID           PRIMARY KEY,
    portfolio_id            UUID           REFERENCES portfolios (id) ON DELETE CASCADE,
    brief_type              VARCHAR(16)    NOT NULL,
    trigger_type            VARCHAR(16)    NOT NULL,
    status                  VARCHAR(16)    NOT NULL,
    research_state          VARCHAR(24),
    period_start            TIMESTAMPTZ    NOT NULL,
    period_end              TIMESTAMPTZ    NOT NULL,
    zone                    VARCHAR(64)    NOT NULL,
    business_date           DATE           NOT NULL,
    partial_period          BOOLEAN        NOT NULL DEFAULT false,
    generated_at            TIMESTAMPTZ    NOT NULL,
    source_cutoff           TIMESTAMPTZ    NOT NULL,
    language                VARCHAR(8)     NOT NULL,
    content                 JSONB          NOT NULL DEFAULT '{}'::jsonb,
    limitations             JSONB          NOT NULL DEFAULT '[]'::jsonb,
    ai_status               VARCHAR(24)    NOT NULL,
    model                   VARCHAR(200),
    prompt_version          VARCHAR(16)    NOT NULL,
    index_version           VARCHAR(200),
    config_version          VARCHAR(32)    NOT NULL,
    input_fingerprint       VARCHAR(64),
    portfolio_snapshot      JSONB,
    snapshot_hash           VARCHAR(64),
    evidence_status         VARCHAR(16),
    error_code              VARCHAR(40),
    error_summary           VARCHAR(500),
    input_tokens            INTEGER,
    output_tokens           INTEGER,
    revision                INTEGER        NOT NULL,
    root_brief_id           UUID           NOT NULL,
    previous_revision_id    UUID           REFERENCES briefs (id) ON DELETE SET NULL,
    job_id                  UUID           REFERENCES brief_jobs (id) ON DELETE SET NULL,
    created_at              TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_briefs_type CHECK (brief_type IN ('DAILY')),
    CONSTRAINT ck_briefs_trigger CHECK (trigger_type IN ('SCHEDULED', 'MANUAL', 'REGENERATION')),
    CONSTRAINT ck_briefs_status CHECK (status IN ('COMPLETED', 'PARTIAL', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_briefs_research_state CHECK (research_state IN ('NEW_RESEARCH', 'NO_MATERIAL_RESEARCH', 'PENDING_ONLY')),
    CONSTRAINT ck_briefs_period CHECK (period_end > period_start),
    CONSTRAINT ck_briefs_ai_status CHECK (ai_status IN ('COMPLETED', 'NOT_CONFIGURED', 'FAILED', 'SKIPPED_NO_EVIDENCE', 'REUSED')),
    CONSTRAINT ck_briefs_evidence_status CHECK (evidence_status IN ('SUPPORTED', 'PARTIAL', 'INSUFFICIENT')),
    CONSTRAINT ck_briefs_hash CHECK (snapshot_hash IS NULL OR snapshot_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_briefs_revision CHECK (revision >= 1 AND (revision = 1) = (root_brief_id = id)),
    CONSTRAINT ck_briefs_tokens CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0)),
    CONSTRAINT uk_briefs_revision UNIQUE (root_brief_id, revision)
);

-- At most one scheduled brief per scope (portfolio, or none), period and configuration.
CREATE UNIQUE INDEX uk_briefs_scheduled
    ON briefs (coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid), period_start, period_end, config_version)
    WHERE trigger_type = 'SCHEDULED';
-- One revision chain per scope and period: later generations for the same period append revisions.
CREATE UNIQUE INDEX uk_briefs_chain
    ON briefs (coalesce(portfolio_id, '00000000-0000-0000-0000-000000000000'::uuid), period_start, period_end)
    WHERE revision = 1;
CREATE INDEX ix_briefs_list ON briefs (period_end DESC, created_at DESC);
CREATE INDEX ix_briefs_portfolio ON briefs (portfolio_id, period_end DESC);
CREATE INDEX ix_briefs_root ON briefs (root_brief_id, revision DESC);

ALTER TABLE brief_jobs
    ADD CONSTRAINT fk_brief_jobs_brief FOREIGN KEY (brief_id) REFERENCES briefs (id) ON DELETE SET NULL,
    ADD CONSTRAINT fk_brief_jobs_previous FOREIGN KEY (previous_brief_id) REFERENCES briefs (id) ON DELETE CASCADE;

CREATE TABLE brief_sources (
    id                  UUID           PRIMARY KEY,
    brief_id            UUID           NOT NULL REFERENCES briefs (id) ON DELETE CASCADE,
    position            INTEGER        NOT NULL,
    role                VARCHAR(16)    NOT NULL,
    label               VARCHAR(8),
    source_type         VARCHAR(16)    NOT NULL,
    document_id         UUID           REFERENCES document (id) ON DELETE SET NULL,
    source_message_id   UUID           REFERENCES source_messages (id) ON DELETE SET NULL,
    chunk_id            UUID,
    chunk_sha256        VARCHAR(64),
    title               VARCHAR(1000),
    brokerage           VARCHAR(200),
    published_on        DATE,
    date_basis          VARCHAR(16),
    sent_at             TIMESTAMPTZ,
    imported_at         TIMESTAMPTZ,
    indexed_at          TIMESTAMPTZ,
    page_start          INTEGER,
    page_end            INTEGER,
    section             VARCHAR(500),
    location            VARCHAR(500),
    excerpt             TEXT,
    symbols             TEXT[]         NOT NULL DEFAULT '{}',
    relevance           VARCHAR(16),
    index_state         VARCHAR(16),
    old_publication     BOOLEAN        NOT NULL DEFAULT false,
    late_arrival        BOOLEAN        NOT NULL DEFAULT false,
    received_via        UUID[]         NOT NULL DEFAULT '{}',

    CONSTRAINT ck_brief_sources_role CHECK (role IN ('CITED', 'INCLUDED', 'PENDING', 'OMITTED')),
    CONSTRAINT ck_brief_sources_type CHECK (source_type IN ('DOCUMENT', 'EMAIL')),
    CONSTRAINT ck_brief_sources_label CHECK ((role IN ('CITED', 'INCLUDED')) = (label IS NOT NULL)),
    CONSTRAINT ck_brief_sources_relevance CHECK (relevance IN ('DIRECT', 'MENTION', 'MARKET', 'OTHER')),
    CONSTRAINT ck_brief_sources_date_basis CHECK (date_basis IN ('PUBLISHED', 'EMAIL_SENT')),
    CONSTRAINT uk_brief_sources_position UNIQUE (brief_id, position)
);

CREATE INDEX ix_brief_sources_document ON brief_sources (document_id) WHERE document_id IS NOT NULL;
CREATE INDEX ix_brief_sources_message ON brief_sources (source_message_id) WHERE source_message_id IS NOT NULL;
