UPDATE transactions
SET mcc = regexp_replace(description, '.*MCC:[[:space:]]*([0-9]{4}).*', '\\1')
WHERE mcc IS NULL
  AND description ~ 'MCC:[[:space:]]*[0-9]{4}';

UPDATE transactions AS transaction
SET category_code = mapping.category_code,
    updated_at = now()
FROM mcc_mappings AS mapping
WHERE transaction.mcc = mapping.mcc
  AND transaction.category_manual = FALSE;
