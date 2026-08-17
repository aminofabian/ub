-- M-Pesa till payer identity: sequential customer numbers, first/last name,
-- masked MSISDN handles, and inbound webhook payer fields.

ALTER TABLE customers
  ADD COLUMN customer_no BIGINT NULL AFTER business_id,
  ADD COLUMN first_name VARCHAR(120) NULL AFTER name,
  ADD COLUMN last_name VARCHAR(120) NULL AFTER first_name,
  ADD COLUMN first_name_norm VARCHAR(120) NULL AFTER last_name,
  ADD COLUMN last_name_norm VARCHAR(120) NULL AFTER first_name_norm,
  ADD COLUMN origin VARCHAR(32) NOT NULL DEFAULT 'staff' AFTER last_name_norm,
  ADD COLUMN mpesa_identity_key VARCHAR(280) NULL AFTER origin,
  ADD COLUMN mpesa_name_updated_at TIMESTAMP NULL AFTER mpesa_identity_key;

UPDATE customers
SET first_name = TRIM(SUBSTRING_INDEX(name, ' ', 1)),
    last_name = NULLIF(TRIM(CASE
      WHEN LOCATE(' ', TRIM(name)) > 0 THEN SUBSTRING(TRIM(name), LOCATE(' ', TRIM(name)) + 1)
      ELSE ''
    END), '')
WHERE (first_name IS NULL OR first_name = '')
  AND name IS NOT NULL
  AND TRIM(name) <> '';

UPDATE customers
SET first_name_norm = UPPER(first_name),
    last_name_norm = UPPER(last_name)
WHERE first_name IS NOT NULL;

-- Sequential IDs per business, oldest first.
UPDATE customers c
JOIN (
  SELECT id, ROW_NUMBER() OVER (PARTITION BY business_id ORDER BY created_at ASC, id ASC) AS rn
  FROM customers
) ranked ON ranked.id = c.id
SET c.customer_no = ranked.rn
WHERE c.customer_no IS NULL;

ALTER TABLE customers
  ADD UNIQUE KEY uq_customers_business_customer_no (business_id, customer_no),
  ADD UNIQUE KEY uq_customers_business_mpesa_identity (business_id, mpesa_identity_key),
  ADD INDEX idx_customers_business_origin (business_id, origin);

ALTER TABLE customer_phones
  MODIFY COLUMN phone VARCHAR(32) NULL,
  ADD COLUMN masked_msisdn VARCHAR(32) NULL AFTER phone,
  ADD COLUMN mask_fingerprint VARCHAR(64) NULL AFTER masked_msisdn,
  ADD COLUMN assigned_msisdn VARCHAR(32) NULL AFTER mask_fingerprint;

ALTER TABLE customer_phones
  ADD INDEX idx_customer_phones_mask_fp (business_id, mask_fingerprint),
  ADD INDEX idx_customer_phones_assigned (business_id, assigned_msisdn);

ALTER TABLE inbound_till_payments
  ADD COLUMN payer_first_name VARCHAR(120) NULL AFTER phone,
  ADD COLUMN payer_last_name VARCHAR(120) NULL AFTER payer_first_name,
  ADD COLUMN masked_msisdn VARCHAR(32) NULL AFTER payer_last_name,
  ADD COLUMN linked_customer_id CHAR(36) NULL AFTER linked_claim_id;

ALTER TABLE inbound_till_payments
  ADD INDEX idx_itp_linked_customer (business_id, linked_customer_id);
