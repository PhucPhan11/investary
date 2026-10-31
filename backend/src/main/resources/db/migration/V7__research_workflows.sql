-- Phase 5 / Milestone 2: automated research processing. One workflow per source (email or library
-- document) records the stages; automation_jobs is the persistent, idempotent job queue that drives them.
-- The workflow only orchestrates the existing services: links/downloads (research), indexing (knowledge)
-- and matching/assessments (intelligence) keep their own tables.

-- Sources imported before this migration are not processed automatically (manual "process" only).
CREATE TABLE workflow_settings (
    id              SMALLINT      PRIMARY KEY DEFAULT 1,
    activated_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_workflow_settings_single_row CHECK (id = 1)
);
INSERT INTO workflow_settings (id) VALUES (1);

CREATE TABLE research_workflows (
    id                          UUID           PRIMARY KEY,
    source_type                 VARCHAR(16)    NOT NULL,
    source_message_id           UUID           REFERENCES source_messages (id) ON DELETE CASCADE,
    document_id                 UUID           REFERENCES document (id) ON DELETE CASCADE,
    status                      VARCHAR(16)    NOT NULL,
    trigger_type                VARCHAR(24)    NOT NULL,
    processing_version          VARCHAR(16)    NOT NULL,
    gmail_sync_run_id           UUID           REFERENCES gmail_sync_runs (id) ON DELETE SET NULL,
    auto_downloads_requested    INTEGER        NOT NULL DEFAULT 0,
    started_at                  TIMESTAMPTZ    NOT NULL,
    completed_at                TIMESTAMPTZ,
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_research_workflows_source_type CHECK (source_type IN ('EMAIL', 'DOCUMENT')),
    CONSTRAINT ck_research_workflows_source CHECK (
        (source_type = 'EMAIL' AND source_message_id IS NOT NULL AND document_id IS NULL)
        OR (source_type = 'DOCUMENT' AND document_id IS NOT NULL AND source_message_id IS NULL)),
    CONSTRAINT ck_research_workflows_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_research_workflows_trigger CHECK (trigger_type IN ('EMAIL_IMPORT', 'GMAIL', 'DOCUMENT_UPLOAD', 'REPORT_DOWNLOADED',
                                                                     'MANUAL', 'RECONCILE')),
    CONSTRAINT ck_research_workflows_downloads CHECK (auto_downloads_requested >= 0),
    CONSTRAINT uk_research_workflows_message UNIQUE (source_message_id),
    CONSTRAINT uk_research_workflows_document UNIQUE (document_id)
);

CREATE INDEX ix_research_workflows_status ON research_workflows (status, updated_at DESC);
CREATE INDEX ix_research_workflows_sync_run ON research_workflows (gmail_sync_run_id) WHERE gmail_sync_run_id IS NOT NULL;

CREATE TABLE workflow_stages (
    workflow_id     UUID           NOT NULL REFERENCES research_workflows (id) ON DELETE CASCADE,
    stage           VARCHAR(24)    NOT NULL,
    status          VARCHAR(16)    NOT NULL,
    reason          VARCHAR(40),
    counts          JSONB          NOT NULL DEFAULT '{}'::jsonb,
    started_at      TIMESTAMPTZ,
    completed_at    TIMESTAMPTZ,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    PRIMARY KEY (workflow_id, stage),
    CONSTRAINT ck_workflow_stages_stage CHECK (stage IN ('EMAIL_IMPORTED', 'EMAIL_PARSED', 'LINKS_CLASSIFIED', 'DOCUMENTS_PROCESSED',
                                                         'KNOWLEDGE_INDEXED', 'PORTFOLIO_MATCHED', 'IMPACT_ASSESSED', 'COMPLETED')),
    CONSTRAINT ck_workflow_stages_status CHECK (status IN ('PENDING', 'RUNNING', 'COMPLETED', 'PARTIAL', 'FAILED', 'SKIPPED'))
);

CREATE TABLE automation_jobs (
    id                  UUID           PRIMARY KEY,
    job_type            VARCHAR(16)    NOT NULL,
    workflow_id         UUID           NOT NULL REFERENCES research_workflows (id) ON DELETE CASCADE,
    portfolio_id        UUID           REFERENCES portfolios (id) ON DELETE CASCADE,
    idempotency_key     VARCHAR(200)   NOT NULL,
    status              VARCHAR(16)    NOT NULL,
    outcome             VARCHAR(40),
    attempts            INTEGER        NOT NULL DEFAULT 0,
    max_attempts        INTEGER        NOT NULL,
    next_attempt_at     TIMESTAMPTZ    NOT NULL,
    wait_deadline       TIMESTAMPTZ,
    claimed_at          TIMESTAMPTZ,
    claim_token         UUID,
    started_at          TIMESTAMPTZ,
    completed_at        TIMESTAMPTZ,
    error_code          VARCHAR(40),
    error_summary       VARCHAR(500),
    processing_version  VARCHAR(16)    NOT NULL,
    input_tokens        INTEGER,
    output_tokens       INTEGER,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_automation_jobs_type CHECK (job_type IN ('PREPARE', 'DOWNLOADS', 'INDEX', 'MATCH', 'ASSESS')),
    CONSTRAINT ck_automation_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'WAITING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_automation_jobs_attempts CHECK (attempts >= 0 AND max_attempts > 0),
    CONSTRAINT ck_automation_jobs_portfolio CHECK ((job_type = 'ASSESS') = (portfolio_id IS NOT NULL)),
    CONSTRAINT ck_automation_jobs_tokens CHECK ((input_tokens IS NULL OR input_tokens >= 0) AND (output_tokens IS NULL OR output_tokens >= 0)),
    CONSTRAINT uk_automation_jobs_idempotency UNIQUE (idempotency_key)
);

CREATE INDEX ix_automation_jobs_due ON automation_jobs (next_attempt_at) WHERE status IN ('PENDING', 'WAITING');
CREATE INDEX ix_automation_jobs_running ON automation_jobs (claimed_at) WHERE status = 'RUNNING';
CREATE INDEX ix_automation_jobs_workflow ON automation_jobs (workflow_id);
CREATE INDEX ix_automation_jobs_list ON automation_jobs (created_at DESC);
CREATE INDEX ix_automation_jobs_assess_done ON automation_jobs (completed_at) WHERE job_type = 'ASSESS' AND status = 'SUCCEEDED';

CREATE TABLE workflow_portfolio_matches (
    id              UUID           PRIMARY KEY,
    workflow_id     UUID           NOT NULL REFERENCES research_workflows (id) ON DELETE CASCADE,
    portfolio_id    UUID           NOT NULL REFERENCES portfolios (id) ON DELETE CASCADE,
    instrument_id   UUID           REFERENCES instruments (id) ON DELETE CASCADE,
    symbol          VARCHAR(20),
    match_kind      VARCHAR(16)    NOT NULL,
    published_on    DATE,
    snapshot_hash   VARCHAR(64)    NOT NULL,
    matched_at      TIMESTAMPTZ    NOT NULL,

    CONSTRAINT ck_workflow_matches_kind CHECK (match_kind IN ('DIRECT', 'MARKET_CONTEXT')),
    CONSTRAINT ck_workflow_matches_instrument CHECK ((match_kind = 'DIRECT') = (instrument_id IS NOT NULL))
);

CREATE UNIQUE INDEX uk_workflow_matches_direct ON workflow_portfolio_matches (workflow_id, portfolio_id, instrument_id)
    WHERE instrument_id IS NOT NULL;
CREATE UNIQUE INDEX uk_workflow_matches_market ON workflow_portfolio_matches (workflow_id, portfolio_id)
    WHERE instrument_id IS NULL;

-- Assessments created by a workflow keep the source that triggered them and the versions used.
ALTER TABLE impact_assessments
    ADD COLUMN trigger_source_type     VARCHAR(16),
    ADD COLUMN trigger_source_id       UUID,
    ADD COLUMN trigger_content_sha256  VARCHAR(64),
    ADD COLUMN prompt_version          VARCHAR(16),
    ADD COLUMN index_version           VARCHAR(200),
    ADD COLUMN workflow_job_id         UUID REFERENCES automation_jobs (id) ON DELETE SET NULL,
    ADD CONSTRAINT ck_impact_assessments_trigger CHECK (
        (trigger_source_id IS NULL AND trigger_source_type IS NULL AND trigger_content_sha256 IS NULL)
        OR (trigger_source_id IS NOT NULL AND trigger_source_type IN ('EMAIL', 'DOCUMENT') AND trigger_content_sha256 IS NOT NULL));

CREATE UNIQUE INDEX uk_impact_assessments_trigger
    ON impact_assessments (portfolio_id, trigger_source_id, snapshot_hash, trigger_content_sha256)
    WHERE trigger_source_id IS NOT NULL;
