UPDATE transactions
SET mcc = substring(description FROM 'MCC:[[:space:]]*([0-9]{4})')
WHERE description ~ 'MCC:[[:space:]]*[0-9]{4}';

UPDATE transactions AS transaction
SET category_code = mapping.category_code,
    updated_at = now()
FROM mcc_mappings AS mapping
WHERE transaction.mcc = mapping.mcc
  AND transaction.category_manual = FALSE;
