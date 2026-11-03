-- Phase 4: saved portfolio impact assessments. The portfolio snapshot and the evidence references are
-- copies taken when the assessment was made, so a later change can be detected (the assessment is then stale).

CREATE TABLE impact_assessments (
    id                  UUID           PRIMARY KEY,
    portfolio_id        UUID           NOT NULL REFERENCES portfolios (id) ON DELETE CASCADE,
    created_at          TIMESTAMPTZ    NOT NULL DEFAULT now(),
    portfolio_snapshot  JSONB          NOT NULL,
    snapshot_hash       VARCHAR(64)    NOT NULL,
    ai_status           VARCHAR(32)    NOT NULL,
    model               VARCHAR(200),
    overall_summary     TEXT,
    overall_evidence_status VARCHAR(16),
    warnings            JSONB          NOT NULL DEFAULT '[]',

    CONSTRAINT ck_impact_assessments_ai_status CHECK (ai_status IN ('NOT_CONFIGURED', 'COMPLETED', 'SKIPPED_NO_EVIDENCE')),
    CONSTRAINT ck_impact_assessments_hash CHECK (snapshot_hash ~ '^[0-9a-f]{64}$'),
    CONSTRAINT ck_impact_assessments_overall_status CHECK (overall_evidence_status IN ('SUPPORTED', 'PARTIAL', 'INSUFFICIENT'))
);

CREATE INDEX ix_impact_assessments_portfolio ON impact_assessments (portfolio_id, created_at DESC);

CREATE TABLE impact_assessment_items (
    id                      UUID           PRIMARY KEY,
    assessment_id           UUID           NOT NULL REFERENCES impact_assessments (id) ON DELETE CASCADE,
    position                INT            NOT NULL,
    instrument_id           UUID           NOT NULL,
    symbol                  VARCHAR(20)    NOT NULL,
    deterministic_category  VARCHAR(32)    NOT NULL,
    category                VARCHAR(32)    NOT NULL,
    summary                 TEXT,
    ai_interpretation       BOOLEAN        NOT NULL DEFAULT false,
    evidence_status         VARCHAR(16)    NOT NULL,
    evidence                JSONB          NOT NULL DEFAULT '[]',

    CONSTRAINT uk_impact_assessment_items_position UNIQUE (assessment_id, position),
    CONSTRAINT ck_impact_assessment_items_category CHECK (
        deterministic_category IN ('DIRECT_HOLDING', 'MARKET_CONTEXT', 'POTENTIAL_RISK', 'POTENTIAL_OPPORTUNITY',
                                   'MONITORING_ONLY', 'INSUFFICIENT_EVIDENCE')
        AND category IN ('DIRECT_HOLDING', 'MARKET_CONTEXT', 'POTENTIAL_RISK', 'POTENTIAL_OPPORTUNITY',
                         'MONITORING_ONLY', 'INSUFFICIENT_EVIDENCE')),
    CONSTRAINT ck_impact_assessment_items_evidence_status CHECK (evidence_status IN ('SUPPORTED', 'PARTIAL', 'INSUFFICIENT'))
);
