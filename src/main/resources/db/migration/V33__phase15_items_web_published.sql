-- Phase 15 Slice 2 — publish flag for public storefront catalog (see PHASE_15_PLAN.md).

ALTER TABLE items
  ADD COLUMN web_published BOOLEAN NOT NULL DEFAULT FALSE AFTER active;

CREATE INDEX idx_items_business_web_published ON items (business_id, web_published, deleted_at);
