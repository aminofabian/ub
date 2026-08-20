-- Extra stall contacts collected at supplier portal sign-up (second WhatsApp, area).

ALTER TABLE marketplace_suppliers
    ADD COLUMN alt_contact_phone VARCHAR(32) NULL,
    ADD COLUMN contact_location VARCHAR(255) NULL;
