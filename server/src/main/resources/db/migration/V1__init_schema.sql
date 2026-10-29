CREATE TABLE users (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    email         TEXT        NOT NULL,
    password_hash TEXT        NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE UNIQUE INDEX ux_users_email_lower ON users (lower(email));

CREATE TABLE categories (
    code TEXT PRIMARY KEY,
    name TEXT NOT NULL
);

CREATE TABLE mcc_mappings (
    mcc           VARCHAR(4) PRIMARY KEY,
    category_code TEXT       NOT NULL REFERENCES categories (code)
);

CREATE TABLE transactions (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    external_id     VARCHAR(64)   NOT NULL,
    operation_date  DATE          NOT NULL,
    amount          NUMERIC(14,2) NOT NULL,
    currency        CHAR(3)       NOT NULL DEFAULT 'RUB',
    description     TEXT,
    mcc             VARCHAR(4),
    category_code   TEXT          NOT NULL REFERENCES categories (code),
    category_manual BOOLEAN       NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ux_transactions_user_external UNIQUE (user_id, external_id)
);
CREATE INDEX ix_transactions_user_date ON transactions (user_id, operation_date);

CREATE TABLE goals (
    id             UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id        UUID          NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    title          TEXT          NOT NULL,
    target_amount  NUMERIC(14,2) NOT NULL CHECK (target_amount > 0),
    current_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (current_amount >= 0),
    deadline       DATE,
    category_code  TEXT          REFERENCES categories (code),
    created_at     TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_goals_user ON goals (user_id);

CREATE TABLE offers (
    id            UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    source_id     TEXT          NOT NULL UNIQUE,
    title         TEXT          NOT NULL,
    regular_price NUMERIC(14,2),
    sale_price    NUMERIC(14,2),
    currency      CHAR(3)       NOT NULL DEFAULT 'RUB',
    valid_from    DATE,
    valid_to      DATE,
    conditions    TEXT,
    url           TEXT          NOT NULL,
    region        TEXT,
    is_test       BOOLEAN       NOT NULL DEFAULT FALSE,
    checked_at    TIMESTAMPTZ   NOT NULL,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_offers_valid_to ON offers (valid_to);

CREATE TABLE offer_categories (
    offer_id      UUID NOT NULL REFERENCES offers (id) ON DELETE CASCADE,
    category_code TEXT NOT NULL REFERENCES categories (code),
    PRIMARY KEY (offer_id, category_code)
);
