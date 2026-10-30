-- Investment research documents (Phase 1).
-- Original files live in the configured storage root; this table holds metadata only.
CREATE TABLE document (
    id                 UUID         PRIMARY KEY,
    original_filename  VARCHAR(255) NOT NULL,
    storage_key        VARCHAR(100) NOT NULL,
    file_size          BIGINT       NOT NULL,
    mime_type          VARCHAR(100) NOT NULL,
    sha256_checksum    VARCHAR(64)  NOT NULL,
    brokerage_company  VARCHAR(200),
    report_type        VARCHAR(32),
    ticker             VARCHAR(15),
    published_on       DATE,
    uploaded_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uk_document_storage_key UNIQUE (storage_key),
    CONSTRAINT uk_document_sha256_checksum UNIQUE (sha256_checksum),
    CONSTRAINT ck_document_file_size_positive CHECK (file_size > 0),
    CONSTRAINT ck_document_sha256_format CHECK (sha256_checksum ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_document_report_type CHECK (report_type IN (
        'MARKET_OVERVIEW', 'MACROECONOMIC', 'INDUSTRY_ANALYSIS',
        'STOCK_ANALYSIS', 'ETF_RESEARCH', 'OTHER'))
);

CREATE INDEX ix_document_uploaded_at ON document (uploaded_at DESC);
CREATE INDEX ix_document_ticker ON document (ticker);
CREATE INDEX ix_document_report_type ON document (report_type);
