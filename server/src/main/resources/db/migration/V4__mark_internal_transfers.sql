ALTER TABLE transactions
    ADD COLUMN is_internal_transfer BOOLEAN NOT NULL DEFAULT FALSE;

UPDATE transactions
SET is_internal_transfer = TRUE
WHERE description ILIKE '%внутрибанковский перевод%'
   OR description ILIKE '%перевод между счетами%';
