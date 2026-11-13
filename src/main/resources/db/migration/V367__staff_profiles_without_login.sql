-- Staff who never sign in (ADR 0014, ticket T1).
--
-- A barber, stylist or therapist is a staff profile whether or not they have a login, so
-- staff_profiles.user_id becomes nullable. Existing rows keep their user. The two unique keys
-- (uq_staff_profiles_user, uq_staff_profiles_business_user) still allow at most one profile per
-- user: MySQL and MariaDB both permit any number of NULLs in a unique key.
--
-- bookable marks staff who perform services and can be picked on a service sale line.
--
-- staff_profiles is a small table (one row per staff member); this ALTER is not a concern for
-- lock time. Forward fix only: do not make user_id NOT NULL again once login-less rows exist.

-- loginless_name_key holds the lower-cased display name of a current (not terminated) staff
-- member who has no login, and NULL for everyone else. With no account behind them the name is
-- all the till has to tell two such people apart, so it is unique per business; the key also
-- makes a double-tapped or retried "add" harmless. Rows with a login never set it.

ALTER TABLE staff_profiles
  MODIFY COLUMN user_id CHAR(36) NULL,
  ADD COLUMN bookable BOOLEAN NOT NULL DEFAULT FALSE,
  ADD COLUMN loginless_name_key VARCHAR(255) NULL,
  ADD UNIQUE KEY uq_staff_profiles_loginless_name (business_id, loginless_name_key);
