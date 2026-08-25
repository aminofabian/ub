-- Structured support messages (e.g. storefront order cards for tenant staff).

ALTER TABLE support_messages
  ADD COLUMN message_kind VARCHAR(32) NOT NULL DEFAULT 'TEXT' AFTER body,
  ADD COLUMN payload_json MEDIUMTEXT NULL AFTER message_kind;
