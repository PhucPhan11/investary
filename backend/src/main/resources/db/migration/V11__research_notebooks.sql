-- Phase 6 / Milestone 2: research notebooks. A notebook is a fixed set of library documents and emails the user
-- picked; chat and generated notes only use evidence from those sources. Existing tables are unchanged, except
-- the AI usage purpose list. Deleting a document or email removes it from notebooks; citations keep their copies.

CREATE TABLE notebooks (
    id           UUID           PRIMARY KEY,
    title        VARCHAR(200)   NOT NULL,
    description  VARCHAR(2000),
    version      INTEGER        NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at   TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_notebooks_version CHECK (version >= 0)
);

CREATE INDEX ix_notebooks_updated ON notebooks (updated_at DESC);

CREATE TABLE notebook_sources (
    id                 UUID          PRIMARY KEY,
    notebook_id        UUID          NOT NULL REFERENCES notebooks (id) ON DELETE CASCADE,
    document_id        UUID          REFERENCES document (id) ON DELETE CASCADE,
    source_message_id  UUID          REFERENCES source_messages (id) ON DELETE CASCADE,
    added_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_notebook_sources_one CHECK ((document_id IS NULL) <> (source_message_id IS NULL))
);

CREATE UNIQUE INDEX ux_notebook_sources_document ON notebook_sources (notebook_id, document_id) WHERE document_id IS NOT NULL;
CREATE UNIQUE INDEX ux_notebook_sources_message ON notebook_sources (notebook_id, source_message_id) WHERE source_message_id IS NOT NULL;

-- Chat history. An answer is written together with its question, after the model call.
CREATE TABLE notebook_messages (
    id               UUID           PRIMARY KEY,
    notebook_id      UUID           NOT NULL REFERENCES notebooks (id) ON DELETE CASCADE,
    role             VARCHAR(16)    NOT NULL,
    content          TEXT           NOT NULL,
    evidence_status  VARCHAR(16),
    warnings         JSONB          NOT NULL DEFAULT '[]',
    model            VARCHAR(200),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT clock_timestamp(),

    CONSTRAINT ck_notebook_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT ck_notebook_messages_status CHECK (evidence_status IS NULL OR evidence_status IN ('SUPPORTED', 'PARTIAL', 'INSUFFICIENT'))
);

CREATE INDEX ix_notebook_messages_notebook ON notebook_messages (notebook_id, created_at);

-- Generated notes. Each row is also its own job (claim token, attempts). A DONE note is never changed;
-- regenerating inserts a new row. The fingerprint covers kind, ticker, prompt version and the cited chunks' text.
CREATE TABLE notebook_notes (
    id               UUID           PRIMARY KEY,
    notebook_id      UUID           NOT NULL REFERENCES notebooks (id) ON DELETE CASCADE,
    kind             VARCHAR(16)    NOT NULL,
    ticker           VARCHAR(15),
    status           VARCHAR(16)    NOT NULL,
    fingerprint      VARCHAR(64)    NOT NULL,
    attempts         INTEGER        NOT NULL DEFAULT 0,
    max_attempts     INTEGER        NOT NULL,
    next_attempt_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    claim_token      UUID,
    claimed_at       TIMESTAMPTZ,
    content          JSONB,
    limitations      JSONB          NOT NULL DEFAULT '[]',
    evidence_status  VARCHAR(16),
    model            VARCHAR(200),
    error_code       VARCHAR(64),
    error_summary    VARCHAR(500),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    completed_at     TIMESTAMPTZ,

    CONSTRAINT ck_notebook_notes_kind CHECK (kind IN ('SUMMARY', 'CONSENSUS', 'TIMELINE', 'FAQ')),
    CONSTRAINT ck_notebook_notes_ticker CHECK ((kind IN ('CONSENSUS', 'TIMELINE')) = (ticker IS NOT NULL)),
    CONSTRAINT ck_notebook_notes_status CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED')),
    CONSTRAINT ck_notebook_notes_evidence CHECK (evidence_status IS NULL OR evidence_status IN ('SUPPORTED', 'PARTIAL', 'INSUFFICIENT')),
    CONSTRAINT ck_notebook_notes_attempts CHECK (attempts >= 0 AND max_attempts > 0)
);

CREATE INDEX ix_notebook_notes_notebook ON notebook_notes (notebook_id, created_at DESC);
CREATE INDEX ix_notebook_notes_due ON notebook_notes (next_attempt_at) WHERE status = 'PENDING';
-- At most one active job per notebook, kind and ticker.
CREATE UNIQUE INDEX ux_notebook_notes_active ON notebook_notes (notebook_id, kind, coalesce(ticker, ''))
    WHERE status IN ('PENDING', 'RUNNING');

-- Citations of answers and notes, copied from the stored chunk when written. The source reference becomes NULL when
-- the document or email is deleted; the copy stays and the UI shows the source as unavailable.
CREATE TABLE notebook_citations (
    id                 UUID           PRIMARY KEY,
    message_id         UUID           REFERENCES notebook_messages (id) ON DELETE CASCADE,
    note_id            UUID           REFERENCES notebook_notes (id) ON DELETE CASCADE,
    label              VARCHAR(8)     NOT NULL,
    source_type        VARCHAR(16)    NOT NULL,
    document_id        UUID           REFERENCES document (id) ON DELETE SET NULL,
    source_message_id  UUID           REFERENCES source_messages (id) ON DELETE SET NULL,
    chunk_id           UUID,
    chunk_sha256       VARCHAR(64),
    title              VARCHAR(500)   NOT NULL,
    publisher          VARCHAR(200),
    published_on       DATE,
    date_basis         VARCHAR(16),
    location           VARCHAR(200),
    excerpt            TEXT           NOT NULL,

    CONSTRAINT ck_notebook_citations_owner CHECK ((message_id IS NULL) <> (note_id IS NULL)),
    CONSTRAINT ck_notebook_citations_type CHECK (source_type IN ('DOCUMENT', 'EMAIL'))
);

CREATE INDEX ix_notebook_citations_message ON notebook_citations (message_id) WHERE message_id IS NOT NULL;
CREATE INDEX ix_notebook_citations_note ON notebook_citations (note_id) WHERE note_id IS NOT NULL;

ALTER TABLE ai_usage_daily DROP CONSTRAINT ck_ai_usage_purpose;
ALTER TABLE ai_usage_daily ADD CONSTRAINT ck_ai_usage_purpose CHECK (purpose IN ('ASSESSMENT', 'BRIEF', 'RESEARCH_ASSISTANT',
    'PORTFOLIO_ASSISTANT', 'NOTEBOOK_NOTE', 'INDEXING', 'SEARCH', 'OTHER'));
