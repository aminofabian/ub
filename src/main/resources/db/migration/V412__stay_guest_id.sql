-- ADR 0030 Phase 2 / ADR 0031: optional guest identification captured at check-in for a lodging
-- stay (a guest house often records a National ID or passport). Kept on the stay (pos_drafts), not
-- the shared customer directory, so this sensitive PII lives only on lodging stays. Stored in the
-- clear for now (an owner decision): it is kept out of logs, fixtures and screenshots per the
-- workspace PII rules, and revisiting encryption-at-rest + a retention window is a follow-up (noted
-- in ADR 0031). Nullable; null on every non-lodging order.
ALTER TABLE pos_drafts
    ADD COLUMN guest_id_type   VARCHAR(32)  NULL,
    ADD COLUMN guest_id_number VARCHAR(64)  NULL;
