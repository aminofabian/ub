-- Idempotency marker: set once DomainsReseller RegisterDomain was accepted for the order,
-- so sync retries never fire a duplicate (double-charging) register request.

ALTER TABLE domain_orders
  ADD COLUMN reseller_register_requested_at TIMESTAMP NULL
    AFTER hostafrica_domain_id;
