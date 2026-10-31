-- Два типа целей: накопление (saving) и лимит расходов по категории (spending_limit).
ALTER TABLE goals
    ADD COLUMN type       TEXT        NOT NULL DEFAULT 'saving'
        CONSTRAINT ck_goals_type CHECK (type IN ('saving', 'spending_limit')),
    ADD COLUMN deleted_at TIMESTAMPTZ;
-- У лимита категория обязательна: прогресс backend считает по расходам категории.
ALTER TABLE goals
    ADD CONSTRAINT ck_goals_limit_has_category
        CHECK (type <> 'spending_limit' OR category_code IS NOT NULL);

-- Мягкое удаление синхронизируемых сущностей (ТЗ, раздел 3).
ALTER TABLE transactions ADD COLUMN deleted_at TIMESTAMPTZ;

-- Журнал изменений для sync. Минимальный вариант: push с идемпотентностью по client_change_id.
-- version/cursor для pull заложены, но эндпоинт pull в MVP отложен (см. ADR 0002).
CREATE TABLE sync_changes (
    id               BIGSERIAL   PRIMARY KEY,
    user_id          UUID        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    client_change_id UUID        NOT NULL,
    entity_type      TEXT        NOT NULL CHECK (entity_type IN ('goal', 'transaction_category')),
    entity_id        UUID        NOT NULL,
    operation        TEXT        NOT NULL CHECK (operation IN ('upsert', 'delete')),
    version          BIGINT      NOT NULL DEFAULT 1,
    changed_at       TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT ux_sync_changes_client UNIQUE (user_id, client_change_id)
);
CREATE INDEX ix_sync_changes_user_id_id ON sync_changes (user_id, id);
