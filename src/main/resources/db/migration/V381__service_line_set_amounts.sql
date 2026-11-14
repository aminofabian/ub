-- What a person is paid for one service can be set at the till instead of worked out from their
-- commission plan (ADR 0016, amendment 3): "Mike gets 120 and Lenah 60 for this one".
--
-- The amount for the person who led the service sits on the line; amounts for helpers ride in
-- service_helpers as "staffProfileId:percent:amount". NULL means "by their plan", as before.
-- Both columns are nullable, so every existing line and sale is unchanged.

ALTER TABLE pos_draft_lines
  ADD COLUMN performer_amount DECIMAL(14,2) NULL;

ALTER TABLE sale_items
  ADD COLUMN performer_amount DECIMAL(14,2) NULL;
