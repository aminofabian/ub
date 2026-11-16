-- What a customer can choose on a service (ADR 0025): one-of groups such as hair length, and
-- extras, each adding to the price and to the time. Kept on the service as JSON, like the
-- products it uses (consumables_json). What was chosen is stored in the existing `modifiers`
-- column of pos_draft_lines and sale_items, so no other table changes.
-- Adding a nullable column: no backfill, existing services stay plain.
ALTER TABLE services
  ADD COLUMN options_json TEXT NULL;
