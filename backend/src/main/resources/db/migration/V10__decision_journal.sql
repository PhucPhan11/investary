-- Phase 6 / Milestone 1: investment decision journal. Decisions, their immutable portfolio snapshot, evidence
-- copies, links to existing portfolio transactions and an append-only revision history. Existing tables are
-- unchanged; deleting a portfolio, instrument, transaction or source never deletes a decision (SET NULL + copies).

CREATE TABLE investment_decisions (
    id                       UUID            PRIMARY KEY,
    portfolio_id             UUID            REFERENCES portfolios (id) ON DELETE SET NULL,
    portfolio_name           VARCHAR(200),
    instrument_id            UUID            REFERENCES instruments (id) ON DELETE SET NULL,
    symbol                   VARCHAR(20),
    decision_type            VARCHAR(16)     NOT NULL,
    status                   VARCHAR(16)     NOT NULL,
    decision_date            DATE            NOT NULL,
    title                    VARCHAR(300)    NOT NULL,
    rationale                TEXT,
    expected_outcome         TEXT,
    time_horizon             VARCHAR(200),
    main_assumptions         TEXT,
    risks                    TEXT,
    invalidation_conditions  TEXT,
    review_date              DATE,
    notes                    TEXT,
    intended_quantity        NUMERIC(24, 6),
    intended_allocation_pct  NUMERIC(9, 4),
    intended_price           NUMERIC(24, 6),
    finalized_at             TIMESTAMPTZ,
    executed_at              TIMESTAMPTZ,
    cancelled_at             TIMESTAMPTZ,
    cancel_reason            VARCHAR(2000),
    version                  INTEGER         NOT NULL DEFAULT 0,
    created_at               TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at               TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT ck_investment_decisions_type CHECK (decision_type IN ('BUY', 'SELL', 'HOLD', 'REBALANCE', 'OBSERVE')),
    CONSTRAINT ck_investment_decisions_status CHECK (status IN ('DRAFT', 'DECIDED', 'EXECUTED', 'CANCELLED')),
    CONSTRAINT ck_investment_decisions_intended CHECK (
        (intended_quantity IS NULL OR intended_quantity > 0)
        AND (intended_price IS NULL OR intended_price >= 0)
        AND (intended_allocation_pct IS NULL OR (intended_allocation_pct >= 0 AND intended_allocation_pct <= 100))),
    CONSTRAINT ck_investment_decisions_version CHECK (version >= 0)
);

CREATE INDEX ix_investment_decisions_date ON investment_decisions (decision_date DESC, created_at DESC);
CREATE INDEX ix_investment_decisions_status ON investment_decisions (status);
CREATE INDEX ix_investment_decisions_symbol ON investment_decisions (symbol);
CREATE INDEX ix_investment_decisions_portfolio ON investment_decisions (portfolio_id);

-- Written once, when the decision is finalized; never updated.
CREATE TABLE decision_portfolio_snapshots (
    decision_id     UUID           PRIMARY KEY REFERENCES investment_decisions (id) ON DELETE CASCADE,
    taken_at        TIMESTAMPTZ    NOT NULL,
    valuation_date  DATE           NOT NULL,
    snapshot        JSONB          NOT NULL,

    CONSTRAINT ck_decision_snapshots_object CHECK (jsonb_typeof(snapshot) = 'object')
);

-- One reference column per kind; SET NULL when the source is deleted. source_id keeps the original id and the
-- copied columns keep what the user saw, so a deleted source shows as unavailable and is never replaced.
CREATE TABLE decision_evidence (
    id                 UUID            PRIMARY KEY,
    decision_id        UUID            NOT NULL REFERENCES investment_decisions (id) ON DELETE CASCADE,
    kind               VARCHAR(20)     NOT NULL,
    role               VARCHAR(16)     NOT NULL,
    source_id          UUID            NOT NULL,
    document_id        UUID            REFERENCES document (id) ON DELETE SET NULL,
    source_message_id  UUID            REFERENCES source_messages (id) ON DELETE SET NULL,
    research_item_id   UUID            REFERENCES research_items (id) ON DELETE SET NULL,
    chunk_id           UUID            REFERENCES knowledge_chunks (id) ON DELETE SET NULL,
    assessment_id      UUID            REFERENCES impact_assessments (id) ON DELETE SET NULL,
    content_sha256     VARCHAR(64),
    title              VARCHAR(1000),
    publisher          VARCHAR(200),
    published_on       DATE,
    location           VARCHAR(500),
    excerpt            TEXT,
    note               VARCHAR(2000),
    attached_at        TIMESTAMPTZ     NOT NULL,
    removed_at         TIMESTAMPTZ,
    removed_reason     VARCHAR(2000),

    CONSTRAINT ck_decision_evidence_kind CHECK (kind IN ('DOCUMENT', 'EMAIL', 'RESEARCH_ITEM', 'CITATION', 'IMPACT_ASSESSMENT')),
    CONSTRAINT ck_decision_evidence_role CHECK (role IN ('SUPPORTING', 'CONTRADICTING', 'BACKGROUND')),
    CONSTRAINT ck_decision_evidence_excerpt CHECK (excerpt IS NULL OR char_length(excerpt) <= 1001)
);

CREATE INDEX ix_decision_evidence_decision ON decision_evidence (decision_id, attached_at);
CREATE UNIQUE INDEX uk_decision_evidence_active ON decision_evidence (decision_id, kind, source_id) WHERE removed_at IS NULL;

-- Links to existing portfolio transactions with the trade facts copied at link time. Transactions stay the
-- financial source of truth; nothing here is used in a calculation.
CREATE TABLE decision_transaction_links (
    id                UUID            PRIMARY KEY,
    decision_id       UUID            NOT NULL REFERENCES investment_decisions (id) ON DELETE CASCADE,
    transaction_id    UUID            REFERENCES portfolio_transactions (id) ON DELETE SET NULL,
    transaction_ref   UUID            NOT NULL,
    portfolio_id      UUID            NOT NULL,
    symbol            VARCHAR(20)     NOT NULL,
    transaction_type  VARCHAR(20)     NOT NULL,
    trade_date        DATE            NOT NULL,
    quantity          NUMERIC(24, 6)  NOT NULL,
    price             NUMERIC(24, 6)  NOT NULL,
    note              VARCHAR(2000),
    linked_at         TIMESTAMPTZ     NOT NULL,
    removed_at        TIMESTAMPTZ,
    removed_reason    VARCHAR(2000)
);

CREATE INDEX ix_decision_links_decision ON decision_transaction_links (decision_id, linked_at);
CREATE UNIQUE INDEX uk_decision_links_active ON decision_transaction_links (decision_id, transaction_ref) WHERE removed_at IS NULL;

-- Append-only audit trail of every change to a decision, its evidence and its links.
CREATE TABLE decision_revisions (
    id            UUID           PRIMARY KEY,
    decision_id   UUID           NOT NULL REFERENCES investment_decisions (id) ON DELETE CASCADE,
    sequence      INTEGER        NOT NULL,
    kind          VARCHAR(32)    NOT NULL,
    reason        VARCHAR(2000),
    status_after  VARCHAR(16)    NOT NULL,
    changes       JSONB          NOT NULL DEFAULT '{}'::jsonb,
    created_at    TIMESTAMPTZ    NOT NULL,

    CONSTRAINT uk_decision_revisions_sequence UNIQUE (decision_id, sequence),
    CONSTRAINT ck_decision_revisions_kind CHECK (kind IN ('CREATED', 'EDITED', 'FINALIZED', 'AMENDED', 'EXECUTED', 'CANCELLED',
                                                          'EVIDENCE_ATTACHED', 'EVIDENCE_REMOVED', 'TRANSACTION_LINKED',
                                                          'TRANSACTION_UNLINKED'))
);

-- Revisions and snapshots are immutable. They may only disappear together with their decision (which no API
-- deletes): the cascade runs after the decision row is gone.
CREATE FUNCTION journal_reject_change() RETURNS trigger AS $$
BEGIN
    IF TG_OP = 'DELETE' AND NOT EXISTS (SELECT 1 FROM investment_decisions WHERE id = OLD.decision_id) THEN
        RETURN OLD;
    END IF;
    RAISE EXCEPTION '% is append-only', TG_TABLE_NAME;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER tr_decision_revisions_immutable BEFORE UPDATE OR DELETE ON decision_revisions
    FOR EACH ROW EXECUTE FUNCTION journal_reject_change();

CREATE TRIGGER tr_decision_snapshots_immutable BEFORE UPDATE OR DELETE ON decision_portfolio_snapshots
    FOR EACH ROW EXECUTE FUNCTION journal_reject_change();
