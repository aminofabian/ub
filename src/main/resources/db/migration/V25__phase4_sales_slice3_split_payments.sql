-- Phase 4 Slice 3 — ordered sale_payments for split tenders (PHASE_4_PLAN.md §Slice 3).

ALTER TABLE sale_payments ADD COLUMN sort_order INT NOT NULL DEFAULT 0;
CREATE INDEX idx_sale_payments_sale_order ON sale_payments (sale_id, sort_order);
