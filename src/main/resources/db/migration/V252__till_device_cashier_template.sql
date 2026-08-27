-- Per-till cashier chrome: shelf (tile POS) or ledger (spreadsheet till).
-- Unregistered browsers keep a local preference; registered tills persist here.

ALTER TABLE till_devices
    ADD COLUMN cashier_template VARCHAR(16) NOT NULL DEFAULT 'shelf';
