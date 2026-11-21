-- A counter order can be called by the guest's name ("Kevin"), not only by its number. The name is
-- snapshotted on the kitchen docket when the ticket is written. It is a name, never a phone number:
-- customer phone numbers stay off kitchen tickets.
ALTER TABLE kitchen_tickets
    ADD COLUMN customer_name VARCHAR(120) NULL;
