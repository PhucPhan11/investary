-- Phase 5 / Milestone 4: Automation Center. Runtime setting overrides, in-app notifications, the attempt
-- history of automation jobs and daily AI usage counters. Existing tables are unchanged.

-- One row per settings section ('gmail', 'processing', 'brief', 'ai'); value holds only the fields the user
-- changed. application.yml / environment values remain the defaults.
CREATE TABLE app_settings (
    section         VARCHAR(32)    PRIMARY KEY,
    value           JSONB          NOT NULL DEFAULT '{}'::jsonb,
    version         INTEGER        NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_app_settings_object CHECK (jsonb_typeof(value) = 'object'),
    CONSTRAINT ck_app_settings_version CHECK (version >= 0)
);

CREATE TABLE notifications (
    id              UUID           PRIMARY KEY,
    type            VARCHAR(32)    NOT NULL,
    priority        VARCHAR(8)     NOT NULL,
    title           VARCHAR(200)   NOT NULL,
    message         VARCHAR(1000)  NOT NULL,
    entity_type     VARCHAR(16),
    entity_id       UUID,
    portfolio_id    UUID           REFERENCES portfolios (id) ON DELETE SET NULL,
    dedup_key       VARCHAR(200)   NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    read_at         TIMESTAMPTZ,

    CONSTRAINT ck_notifications_type CHECK (type IN ('NEW_RESEARCH', 'PORTFOLIO_RESEARCH', 'RESEARCH_RISK', 'RESEARCH_CONFLICT',
                                                     'DAILY_BRIEF_READY', 'SYNC_FAILED', 'PROCESSING_FAILED')),
    CONSTRAINT ck_notifications_priority CHECK (priority IN ('LOW', 'NORMAL', 'HIGH')),
    CONSTRAINT ck_notifications_entity CHECK (entity_type IS NULL OR entity_type IN ('EMAIL', 'DOCUMENT', 'BRIEF', 'ASSESSMENT',
                                                                                     'PORTFOLIO', 'JOB', 'BRIEF_JOB', 'GMAIL')),
    CONSTRAINT uk_notifications_dedup UNIQUE (dedup_key)
);

CREATE INDEX ix_notifications_list ON notifications (created_at DESC, id);
CREATE INDEX ix_notifications_unread ON notifications (created_at DESC) WHERE read_at IS NULL;

-- Each claim of an automation job and its result; manual retries are recorded too.
CREATE TABLE automation_job_attempts (
    id              UUID           PRIMARY KEY,
    job_id          UUID           NOT NULL REFERENCES automation_jobs (id) ON DELETE CASCADE,
    attempt         INTEGER        NOT NULL,
    trigger_type    VARCHAR(16)    NOT NULL,
    started_at      TIMESTAMPTZ    NOT NULL,
    finished_at     TIMESTAMPTZ,
    result          VARCHAR(16)    NOT NULL,
    error_code      VARCHAR(40),
    error_summary   VARCHAR(500),

    CONSTRAINT ck_job_attempts_trigger CHECK (trigger_type IN ('AUTO', 'MANUAL_RETRY', 'LEASE_RECOVERY')),
    CONSTRAINT ck_job_attempts_result CHECK (result IN ('RUNNING', 'SUCCEEDED', 'RETRY_SCHEDULED', 'WAITING', 'FAILED',
                                                        'LEASE_EXPIRED', 'RETRY_REQUESTED')),
    CONSTRAINT ck_job_attempts_attempt CHECK (attempt >= 0)
);

CREATE INDEX ix_job_attempts_job ON automation_job_attempts (job_id, started_at);

-- Provider calls per Asia/Ho_Chi_Minh day. Tokens are only those the provider reported.
CREATE TABLE ai_usage_daily (
    usage_date      DATE           NOT NULL,
    operation       VARCHAR(16)    NOT NULL,
    purpose         VARCHAR(24)    NOT NULL,
    model           VARCHAR(200)   NOT NULL,
    requests        BIGINT         NOT NULL DEFAULT 0,
    failures        BIGINT         NOT NULL DEFAULT 0,
    input_tokens    BIGINT         NOT NULL DEFAULT 0,
    output_tokens   BIGINT         NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),

    PRIMARY KEY (usage_date, operation, purpose, model),
    CONSTRAINT ck_ai_usage_operation CHECK (operation IN ('CHAT', 'EMBEDDING')),
    CONSTRAINT ck_ai_usage_purpose CHECK (purpose IN ('ASSESSMENT', 'BRIEF', 'RESEARCH_ASSISTANT', 'PORTFOLIO_ASSISTANT',
                                                      'INDEXING', 'SEARCH', 'OTHER')),
    CONSTRAINT ck_ai_usage_counts CHECK (requests >= 0 AND failures >= 0 AND input_tokens >= 0 AND output_tokens >= 0)
);

-- Briefs whose model part was skipped because a daily AI limit was reached.
ALTER TABLE briefs DROP CONSTRAINT ck_briefs_ai_status;
ALTER TABLE briefs ADD CONSTRAINT ck_briefs_ai_status
    CHECK (ai_status IN ('COMPLETED', 'NOT_CONFIGURED', 'FAILED', 'SKIPPED_NO_EVIDENCE', 'REUSED', 'SKIPPED_LIMIT'));
