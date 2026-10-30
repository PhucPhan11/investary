-- Phase 4: investor profile, goals, instruments, portfolios, transactions and manual price observations.
-- Additive only. Holdings, valuations and allocations are computed from these tables and never stored.

CREATE TABLE investor_profile (
    id                     UUID          PRIMARY KEY,
    experience_level       VARCHAR(16),
    risk_tolerance         VARCHAR(16),
    objectives             TEXT,
    investment_horizon     VARCHAR(16),
    preferred_instruments  TEXT[]        NOT NULL DEFAULT '{}',
    restrictions           TEXT,
    liquidity_needs        TEXT,
    notes                  TEXT,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),

    CONSTRAINT ck_investor_profile_experience CHECK (experience_level IN ('NONE', 'BEGINNER', 'INTERMEDIATE', 'ADVANCED')),
    CONSTRAINT ck_investor_profile_risk CHECK (risk_tolerance IN ('LOW', 'MODERATE', 'HIGH')),
    CONSTRAINT ck_investor_profile_horizon CHECK (investment_horizon IN ('SHORT', 'MEDIUM', 'LONG'))
);

-- Single-user application: at most one profile row.
CREATE UNIQUE INDEX uk_investor_profile_single ON investor_profile ((true));

CREATE TABLE investor_goals (
    id             UUID           PRIMARY KEY,
    name           VARCHAR(200)   NOT NULL,
    description    TEXT,
    target_amount  NUMERIC(24, 4),
    target_date    DATE,
    currency       VARCHAR(3)     NOT NULL DEFAULT 'VND',
    priority       VARCHAR(16)    NOT NULL DEFAULT 'MEDIUM',
    notes          TEXT,
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_investor_goals_amount CHECK (target_amount IS NULL OR target_amount > 0),
    CONSTRAINT ck_investor_goals_currency CHECK (currency ~ '^[A-Z]{3}$'),
    CONSTRAINT ck_investor_goals_priority CHECK (priority IN ('HIGH', 'MEDIUM', 'LOW'))
);

CREATE TABLE instruments (
    id               UUID           PRIMARY KEY,
    symbol           VARCHAR(20)    NOT NULL,
    name             VARCHAR(300)   NOT NULL,
    instrument_type  VARCHAR(16)    NOT NULL,
    exchange         VARCHAR(16),
    currency         VARCHAR(3)     NOT NULL DEFAULT 'VND',
    aliases          TEXT[]         NOT NULL DEFAULT '{}',
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT uk_instruments_symbol UNIQUE (symbol),
    CONSTRAINT ck_instruments_symbol CHECK (symbol ~ '^[A-Z0-9][A-Z0-9._-]{0,19}$'),
    CONSTRAINT ck_instruments_type CHECK (instrument_type IN ('STOCK', 'ETF', 'FUND', 'BOND', 'OTHER')),
    CONSTRAINT ck_instruments_exchange CHECK (exchange IN ('HOSE', 'HNX', 'UPCOM', 'OTHER')),
    CONSTRAINT ck_instruments_currency CHECK (currency ~ '^[A-Z]{3}$')
);

CREATE TABLE portfolios (
    id             UUID           PRIMARY KEY,
    name           VARCHAR(200)   NOT NULL,
    description    TEXT,
    base_currency  VARCHAR(3)     NOT NULL DEFAULT 'VND',
    created_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ    NOT NULL DEFAULT now(),

    CONSTRAINT ck_portfolios_currency CHECK (base_currency ~ '^[A-Z]{3}$')
);

CREATE UNIQUE INDEX uk_portfolios_name ON portfolios (lower(name));

CREATE TABLE portfolio_transactions (
    id                UUID            PRIMARY KEY,
    portfolio_id      UUID            NOT NULL REFERENCES portfolios (id) ON DELETE CASCADE,
    instrument_id     UUID            NOT NULL REFERENCES instruments (id) ON DELETE RESTRICT,
    transaction_type  VARCHAR(20)     NOT NULL,
    trade_date        DATE            NOT NULL,
    quantity          NUMERIC(24, 6)  NOT NULL,
    price             NUMERIC(24, 6)  NOT NULL,
    fees              NUMERIC(24, 6)  NOT NULL DEFAULT 0,
    taxes             NUMERIC(24, 6)  NOT NULL DEFAULT 0,
    notes             TEXT,
    created_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT ck_portfolio_transactions_type CHECK (transaction_type IN ('BUY', 'SELL', 'OPENING_POSITION')),
    CONSTRAINT ck_portfolio_transactions_quantity CHECK (quantity > 0),
    CONSTRAINT ck_portfolio_transactions_price CHECK (price >= 0),
    CONSTRAINT ck_portfolio_transactions_fees CHECK (fees >= 0 AND taxes >= 0)
);

CREATE INDEX ix_portfolio_transactions_portfolio ON portfolio_transactions (portfolio_id, trade_date, created_at);
CREATE INDEX ix_portfolio_transactions_instrument ON portfolio_transactions (instrument_id);

CREATE TABLE instrument_prices (
    id             UUID            PRIMARY KEY,
    instrument_id  UUID            NOT NULL REFERENCES instruments (id) ON DELETE CASCADE,
    price          NUMERIC(24, 6)  NOT NULL,
    as_of          DATE            NOT NULL,
    source         VARCHAR(200)    NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ     NOT NULL DEFAULT now(),

    CONSTRAINT ck_instrument_prices_price CHECK (price > 0),
    CONSTRAINT uk_instrument_prices UNIQUE (instrument_id, as_of, source)
);

CREATE INDEX ix_instrument_prices_latest ON instrument_prices (instrument_id, as_of DESC, created_at DESC);
