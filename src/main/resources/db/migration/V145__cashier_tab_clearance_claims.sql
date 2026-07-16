-- V145: Staff-originated tab clearances reuse public_payment_claims.
-- source = public | cashier; proposed_channel = cash | mpesa (hint for admin approve).

ALTER TABLE public_payment_claims
    ADD COLUMN source VARCHAR(24) NOT NULL DEFAULT 'public',
    ADD COLUMN proposed_channel VARCHAR(16) NULL,
    ADD COLUMN submitted_by_user_id VARCHAR(36) NULL;

CREATE INDEX idx_public_payment_claims_source_status
    ON public_payment_claims (business_id, source, status);
