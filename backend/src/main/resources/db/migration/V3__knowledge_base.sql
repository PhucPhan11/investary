-- Phase 3: research knowledge base. Additive only; Phase 1/2 tables are unchanged.
-- Everything here is derived data: it can be dropped and rebuilt from the documents and emails.

CREATE EXTENSION IF NOT EXISTS vector;

-- One row per indexable source (a library document or an imported email). The row is both the
-- source's index state and its indexing job, claimed with FOR UPDATE SKIP LOCKED like download_jobs.
CREATE TABLE knowledge_sources (
    id                 UUID          PRIMARY KEY,
    source_type        VARCHAR(16)   NOT NULL,
    document_id        UUID          REFERENCES document (id) ON DELETE CASCADE,
    source_message_id  UUID          REFERENCES source_messages (id) ON DELETE CASCADE,
    status             VARCHAR(16)   NOT NULL,
    status_reason      VARCHAR(32),
    index_version      VARCHAR(200),
    content_sha256     VARCHAR(64),
    chunk_count        INT           NOT NULL DEFAULT 0,
    page_count         INT,
    attempts           INT           NOT NULL DEFAULT 0,
    max_attempts       INT           NOT NULL,
    next_attempt_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    claimed_at         TIMESTAMPTZ,
    claim_token        UUID,
    last_error         TEXT,
    requested_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at         TIMESTAMPTZ,
    completed_at       TIMESTAMPTZ,
    updated_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uk_knowledge_sources_document UNIQUE (document_id),
    CONSTRAINT uk_knowledge_sources_message UNIQUE (source_message_id),
    CONSTRAINT ck_knowledge_sources_type CHECK (source_type IN ('DOCUMENT', 'EMAIL')),
    CONSTRAINT ck_knowledge_sources_reference CHECK (
        (source_type = 'DOCUMENT' AND document_id IS NOT NULL AND source_message_id IS NULL)
        OR (source_type = 'EMAIL' AND source_message_id IS NOT NULL AND document_id IS NULL)),
    CONSTRAINT ck_knowledge_sources_status CHECK (status IN ('PENDING', 'PROCESSING', 'INDEXED', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_knowledge_sources_attempts CHECK (attempts >= 0 AND max_attempts > 0)
);

CREATE INDEX ix_knowledge_sources_pending ON knowledge_sources (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_knowledge_sources_processing ON knowledge_sources (claimed_at) WHERE status = 'PROCESSING';

-- Searchable chunks. Metadata columns are a snapshot taken at index time (refreshed by a reindex) so
-- retrieval can filter without joining every source table. Only chunks whose index_version matches
-- the active configuration are searched, so vectors from different models are never compared.
CREATE TABLE knowledge_chunks (
    id                    UUID          PRIMARY KEY,
    knowledge_source_id   UUID          NOT NULL REFERENCES knowledge_sources (id) ON DELETE CASCADE,
    chunk_index           INT           NOT NULL,
    content               TEXT          NOT NULL,
    content_sha256        VARCHAR(64)   NOT NULL,
    page_start            INT,
    page_end              INT,
    section               VARCHAR(500),
    char_start            INT,
    char_end              INT,
    source_title          VARCHAR(1000),
    brokerage             VARCHAR(200),
    published_on          DATE,
    date_basis            VARCHAR(16),
    tickers               TEXT[]        NOT NULL DEFAULT '{}',
    source_ticker         VARCHAR(15),
    language              VARCHAR(8),
    embedding             vector        NOT NULL,
    embedding_model       VARCHAR(200)  NOT NULL,
    embedding_dimensions  INT           NOT NULL,
    index_version         VARCHAR(200)  NOT NULL,
    search_vector         tsvector      NOT NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uk_knowledge_chunks_position UNIQUE (knowledge_source_id, chunk_index),
    CONSTRAINT ck_knowledge_chunks_pages CHECK (page_start IS NULL OR (page_start >= 1 AND page_end >= page_start)),
    CONSTRAINT ck_knowledge_chunks_date_basis CHECK (date_basis IN ('PUBLISHED', 'EMAIL_SENT')),
    CONSTRAINT ck_knowledge_chunks_dimensions CHECK (embedding_dimensions > 0 AND vector_dims(embedding) = embedding_dimensions)
);

CREATE INDEX ix_knowledge_chunks_filters ON knowledge_chunks (index_version, brokerage, published_on);
CREATE INDEX ix_knowledge_chunks_tickers ON knowledge_chunks USING gin (tickers);
CREATE INDEX ix_knowledge_chunks_search ON knowledge_chunks USING gin (search_vector);
CREATE INDEX ix_knowledge_chunks_hash ON knowledge_chunks (content_sha256, embedding_model, embedding_dimensions);
-- No approximate-nearest-neighbour index yet: exact search is fast at personal scale and works for any
-- dimension. Add an HNSW expression index per dimension when the corpus grows (docs/phase-3-implementation.md).
