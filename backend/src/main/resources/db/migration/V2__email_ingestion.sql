-- Phase 2: email ingestion and research provenance. Additive only; the Phase 1 document table is unchanged.
-- Original EML files live in the email storage root; these tables hold metadata and derived data.

CREATE TABLE source_messages (
    id                   UUID          PRIMARY KEY,
    source_type          VARCHAR(32)   NOT NULL,
    storage_key          VARCHAR(100)  NOT NULL,
    original_filename    VARCHAR(255)  NOT NULL,
    file_size            BIGINT        NOT NULL,
    content_sha256       VARCHAR(64)   NOT NULL,
    message_id           VARCHAR(998),
    sender_address       VARCHAR(320),
    sender_name          VARCHAR(500),
    recipient_addresses  TEXT,
    subject              TEXT,
    sent_at              TIMESTAMPTZ,
    imported_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    brokerage            VARCHAR(100),
    detected_languages   VARCHAR(32),
    html_content         TEXT,
    normalized_text      TEXT,
    parsing_status       VARCHAR(16)   NOT NULL,
    parsing_error        TEXT,

    CONSTRAINT uk_source_messages_storage_key UNIQUE (storage_key),
    CONSTRAINT uk_source_messages_content_sha256 UNIQUE (content_sha256),
    CONSTRAINT ck_source_messages_sha256_format CHECK (content_sha256 ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_source_messages_file_size_positive CHECK (file_size > 0),
    CONSTRAINT ck_source_messages_source_type CHECK (source_type IN ('EML_UPLOAD')),
    CONSTRAINT ck_source_messages_parsing_status CHECK (parsing_status IN ('PARSED', 'PARTIAL', 'FAILED'))
);

CREATE INDEX ix_source_messages_imported_at ON source_messages (imported_at DESC);
CREATE INDEX ix_source_messages_sent_at ON source_messages (sent_at DESC);
CREATE INDEX ix_source_messages_sender_address ON source_messages (lower(sender_address));
CREATE INDEX ix_source_messages_message_id ON source_messages (message_id);

CREATE TABLE source_attachments (
    id                 UUID          PRIMARY KEY,
    source_message_id  UUID          NOT NULL REFERENCES source_messages (id) ON DELETE CASCADE,
    position           INT           NOT NULL,
    filename           VARCHAR(255),
    content_type       VARCHAR(255)  NOT NULL,
    size_bytes         BIGINT        NOT NULL,
    sha256_checksum    VARCHAR(64)   NOT NULL,
    disposition        VARCHAR(16)   NOT NULL,
    content_id         VARCHAR(255),
    document_id        UUID          REFERENCES document (id) ON DELETE SET NULL,
    import_error       TEXT,

    CONSTRAINT uk_source_attachments_position UNIQUE (source_message_id, position),
    CONSTRAINT ck_source_attachments_disposition CHECK (disposition IN ('ATTACHMENT', 'INLINE'))
);

CREATE INDEX ix_source_attachments_document_id ON source_attachments (document_id);

CREATE TABLE source_links (
    id                   UUID          PRIMARY KEY,
    source_message_id    UUID          NOT NULL REFERENCES source_messages (id) ON DELETE CASCADE,
    position             INT           NOT NULL,
    original_url         TEXT          NOT NULL,
    target_url           TEXT          NOT NULL,
    host                 VARCHAR(255)  NOT NULL,
    display_text         TEXT,
    section              VARCHAR(500),
    language             VARCHAR(8),
    category             VARCHAR(32)   NOT NULL,
    classification_rule  VARCHAR(100)  NOT NULL,
    ticker_hint          VARCHAR(15),
    source_brokerage     VARCHAR(100),
    status               VARCHAR(16)   NOT NULL,
    discovered_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_attempt_at      TIMESTAMPTZ,
    error_message        TEXT,
    document_id          UUID          REFERENCES document (id) ON DELETE SET NULL,

    CONSTRAINT uk_source_links_target UNIQUE (source_message_id, target_url),
    CONSTRAINT ck_source_links_category CHECK (category IN (
        'RESEARCH_REPORT', 'STOCK_RESEARCH', 'MARKET_REPORT', 'NAVIGATION', 'PROMOTION', 'OTHER')),
    CONSTRAINT ck_source_links_status CHECK (status IN (
        'DISCOVERED', 'READY', 'DOWNLOADING', 'DOWNLOADED', 'FAILED', 'SKIPPED'))
);

CREATE INDEX ix_source_links_message ON source_links (source_message_id, position);
CREATE INDEX ix_source_links_status ON source_links (status);
CREATE INDEX ix_source_links_document_id ON source_links (document_id);

-- Which documents came from which source message. A document may be referenced by many messages.
CREATE TABLE message_documents (
    source_message_id     UUID          NOT NULL REFERENCES source_messages (id) ON DELETE CASCADE,
    document_id           UUID          NOT NULL REFERENCES document (id) ON DELETE CASCADE,
    relationship_type     VARCHAR(32)   NOT NULL,
    source_link_id        UUID          REFERENCES source_links (id) ON DELETE SET NULL,
    source_attachment_id  UUID          REFERENCES source_attachments (id) ON DELETE SET NULL,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT pk_message_documents PRIMARY KEY (source_message_id, document_id, relationship_type),
    CONSTRAINT ck_message_documents_relationship CHECK (relationship_type IN ('ATTACHMENT', 'LINKED_REPORT'))
);

CREATE INDEX ix_message_documents_document_id ON message_documents (document_id);

-- Deterministically extracted research information. Always derived data; the source message stays authoritative.
CREATE TABLE research_items (
    id                 UUID          PRIMARY KEY,
    source_message_id  UUID          NOT NULL REFERENCES source_messages (id) ON DELETE CASCADE,
    item_type          VARCHAR(32)   NOT NULL,
    ticker             VARCHAR(15),
    brokerage          VARCHAR(100),
    published_at       TIMESTAMPTZ,
    title              VARCHAR(500),
    content            TEXT          NOT NULL,
    structured_data    JSONB,
    extraction_method  VARCHAR(50)   NOT NULL,
    source_location    VARCHAR(500)  NOT NULL,
    language           VARCHAR(8),
    created_at         TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_research_items_type CHECK (item_type IN (
        'MARKET_UPDATE', 'MACRO_UPDATE', 'INDUSTRY_UPDATE', 'COMPANY_NEWS', 'STOCK_RECOMMENDATION'))
);

CREATE INDEX ix_research_items_message ON research_items (source_message_id);
CREATE INDEX ix_research_items_ticker ON research_items (ticker);
CREATE INDEX ix_research_items_type ON research_items (item_type);

-- Background download queue: one job per link, claimed with FOR UPDATE SKIP LOCKED.
CREATE TABLE download_jobs (
    id               UUID          PRIMARY KEY,
    source_link_id   UUID          NOT NULL REFERENCES source_links (id) ON DELETE CASCADE,
    status           VARCHAR(16)   NOT NULL,
    attempts         INT           NOT NULL DEFAULT 0,
    max_attempts     INT           NOT NULL,
    next_attempt_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    claimed_at       TIMESTAMPTZ,
    claim_token      UUID,
    last_error       TEXT,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT uk_download_jobs_link UNIQUE (source_link_id),
    CONSTRAINT ck_download_jobs_status CHECK (status IN ('PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED')),
    CONSTRAINT ck_download_jobs_attempts CHECK (attempts >= 0 AND max_attempts > 0)
);

CREATE INDEX ix_download_jobs_pending ON download_jobs (next_attempt_at) WHERE status = 'PENDING';
CREATE INDEX ix_download_jobs_running ON download_jobs (claimed_at) WHERE status = 'RUNNING';
