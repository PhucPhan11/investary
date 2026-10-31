-- Phase 5 / Milestone 1: Gmail integration and synchronization. Messages are imported through the Phase 2
-- import (original bytes, SHA-256 dedup); these tables hold the connection, the source rules, the
-- Gmail message mapping (dedup key per account) and the sync runs.

ALTER TABLE source_messages DROP CONSTRAINT ck_source_messages_source_type;
ALTER TABLE source_messages ADD CONSTRAINT ck_source_messages_source_type CHECK (source_type IN ('EML_UPLOAD', 'GMAIL'));

CREATE TABLE gmail_accounts (
    id                          UUID           PRIMARY KEY,
    email_address               VARCHAR(320)   NOT NULL,
    status                      VARCHAR(24)    NOT NULL,
    refresh_token_ciphertext    BYTEA,
    token_key_id                VARCHAR(16),
    granted_scopes              TEXT,
    connected_at                TIMESTAMPTZ,
    disconnected_at             TIMESTAMPTZ,
    history_id                  VARCHAR(20),
    pending_history_id          VARCHAR(20),
    initial_sync_completed_at   TIMESTAMPTZ,
    last_sync_started_at        TIMESTAMPTZ,
    last_sync_success_at        TIMESTAMPTZ,
    last_sync_error_code        VARCHAR(40),
    last_sync_error             TEXT,
    sync_lock_token             UUID,
    sync_lock_until             TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_gmail_accounts_status CHECK (status IN ('CONNECTED', 'RECONNECT_REQUIRED', 'DISCONNECTED')),
    CONSTRAINT ck_gmail_accounts_history_id CHECK (history_id ~ '^[0-9]{1,20}$'),
    CONSTRAINT ck_gmail_accounts_pending_history_id CHECK (pending_history_id ~ '^[0-9]{1,20}$'),
    CONSTRAINT ck_gmail_accounts_token CHECK (status <> 'CONNECTED' OR (refresh_token_ciphertext IS NOT NULL AND token_key_id IS NOT NULL))
);

CREATE UNIQUE INDEX uk_gmail_accounts_email ON gmail_accounts (lower(email_address));

CREATE TABLE email_source_rules (
    id                  UUID           PRIMARY KEY,
    name                VARCHAR(100)   NOT NULL,
    sender_addresses    TEXT[]         NOT NULL DEFAULT '{}',
    sender_domains      TEXT[]         NOT NULL DEFAULT '{}',
    subject_keywords    TEXT[]         NOT NULL DEFAULT '{}',
    gmail_query         VARCHAR(500),
    enabled             BOOLEAN        NOT NULL DEFAULT true,
    auto_import         BOOLEAN        NOT NULL DEFAULT true,
    auto_pdf_download   BOOLEAN        NOT NULL DEFAULT false,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_email_source_rules_sender CHECK (cardinality(sender_addresses) + cardinality(sender_domains) > 0)
);

CREATE UNIQUE INDEX uk_email_source_rules_name ON email_source_rules (lower(name));

CREATE TABLE gmail_sync_runs (
    id                  UUID           PRIMARY KEY,
    account_id          UUID           NOT NULL REFERENCES gmail_accounts (id) ON DELETE CASCADE,
    trigger_type        VARCHAR(16)    NOT NULL,
    mode                VARCHAR(16),
    status              VARCHAR(16)    NOT NULL,
    started_at          TIMESTAMPTZ    NOT NULL,
    finished_at         TIMESTAMPTZ,
    listed              INT            NOT NULL DEFAULT 0,
    imported            INT            NOT NULL DEFAULT 0,
    duplicates          INT            NOT NULL DEFAULT 0,
    not_imported        INT            NOT NULL DEFAULT 0,
    skipped             INT            NOT NULL DEFAULT 0,
    rejected            INT            NOT NULL DEFAULT 0,
    start_history_id    VARCHAR(20),
    end_history_id      VARCHAR(20),
    error_code          VARCHAR(40),
    error_message       TEXT,
    warning             TEXT,

    CONSTRAINT ck_gmail_sync_runs_trigger CHECK (trigger_type IN ('MANUAL', 'SCHEDULED')),
    CONSTRAINT ck_gmail_sync_runs_mode CHECK (mode IN ('INITIAL', 'INCREMENTAL', 'RECOVERY')),
    CONSTRAINT ck_gmail_sync_runs_status CHECK (status IN ('RUNNING', 'SUCCEEDED', 'PARTIAL', 'FAILED')),
    CONSTRAINT ck_gmail_sync_runs_counts CHECK (listed >= 0 AND imported >= 0 AND duplicates >= 0
        AND not_imported >= 0 AND skipped >= 0 AND rejected >= 0)
);

CREATE INDEX ix_gmail_sync_runs_account ON gmail_sync_runs (account_id, started_at DESC);
CREATE INDEX ix_gmail_sync_runs_running ON gmail_sync_runs (account_id) WHERE status = 'RUNNING';

CREATE TABLE gmail_messages (
    id                  UUID           PRIMARY KEY,
    account_id          UUID           NOT NULL REFERENCES gmail_accounts (id) ON DELETE CASCADE,
    gmail_message_id    VARCHAR(64)    NOT NULL,
    thread_id           VARCHAR(64),
    history_id          VARCHAR(20),
    internal_date       TIMESTAMPTZ,
    status              VARCHAR(16)    NOT NULL,
    source_rule_id      UUID           REFERENCES email_source_rules (id) ON DELETE SET NULL,
    source_message_id   UUID           REFERENCES source_messages (id) ON DELETE SET NULL,
    sync_run_id         UUID           REFERENCES gmail_sync_runs (id) ON DELETE SET NULL,
    error_code          VARCHAR(40),
    processed_at        TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_gmail_messages_account_message UNIQUE (account_id, gmail_message_id),
    CONSTRAINT ck_gmail_messages_status CHECK (status IN ('IMPORTED', 'DUPLICATE', 'NOT_IMPORTED', 'REJECTED'))
);

CREATE INDEX ix_gmail_messages_source_message ON gmail_messages (source_message_id);
CREATE INDEX ix_gmail_messages_thread ON gmail_messages (account_id, thread_id);
