-- V193: One M-Pesa receipt may satisfy at most one payment claim.
-- The same buygoods receipt must not auto-approve a second (or third) claim once it has
-- been approved, so SUBMITTED/APPROVED references are unique per business. Rejected or
-- issued claims map to NULL and stay unreserved (a customer can resubmit after a rejection).

ALTER TABLE public_payment_claims
  ADD COLUMN active_reference VARCHAR(128) GENERATED ALWAYS AS (
      CASE WHEN status IN ('submitted', 'approved') THEN submitted_reference ELSE NULL END
  ) STORED;

ALTER TABLE public_payment_claims
  ADD UNIQUE KEY uq_ppc_business_active_reference (business_id, active_reference);
