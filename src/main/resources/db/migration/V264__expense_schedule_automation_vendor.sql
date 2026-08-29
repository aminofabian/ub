-- Fixed costs Phase 2b/3: remind mode + landlord/vendor details on schedules.

ALTER TABLE expense_schedules
    ADD COLUMN automation_mode VARCHAR(16) NOT NULL DEFAULT 'auto_post'
        COMMENT 'auto_post | remind';

ALTER TABLE expense_schedules
    ADD COLUMN vendor_contact_name VARCHAR(128) NULL;

ALTER TABLE expense_schedules
    ADD COLUMN vendor_phone VARCHAR(32) NULL;

ALTER TABLE expense_schedules
    ADD COLUMN vendor_mpesa_number VARCHAR(32) NULL;

ALTER TABLE expense_schedules
    ADD COLUMN vendor_lease_note VARCHAR(1000) NULL;
