-- Card terminal clearing account for POS card tender (butchery / retail).
INSERT INTO ledger_accounts (id, business_id, code, name, account_type, parent_id, version, created_at, updated_at)
SELECT UUID(), b.id, '1025', 'Card terminal clearing', 'asset', NULL, 0, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM businesses b
WHERE b.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1 FROM ledger_accounts la WHERE la.business_id = b.id AND la.code = '1025'
  );
