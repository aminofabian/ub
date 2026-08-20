-- Weighted cart toggle for cashiers: default ON (admin can still disable in settings).
-- Ensures existing tenants get the scale button on the POS.
--
-- MySQL + MariaDB compatible. The previous content used CAST(... AS JSON),
-- which MySQL accepts but MariaDB rejects (MariaDB has no JSON type; "JSON"
-- columns are LONGTEXT and CAST(x AS JSON) is a syntax error). That blocked
-- the whole migration history on MariaDB — including the desktop app's bundled
-- MariaDB 10.11. JSON_MERGE_PATCH is supported by MySQL 8.0+ and MariaDB 10.7+
-- and preserves any existing featureFlags while setting the toggle.
--
-- NOTE for MySQL deployments that already applied the earlier V143: its
-- checksum changed, so run `flyway repair` once before the next deploy
-- (repair realigns the stored checksum; repair-on-migrate is not supported by
-- the Flyway community edition used here).

UPDATE businesses
SET settings = CASE
    WHEN settings IS NULL OR TRIM(settings) = '' THEN
        JSON_OBJECT('featureFlags', JSON_OBJECT('pos.cashier_weighed_toggle', true))
    ELSE
        JSON_MERGE_PATCH(
            settings,
            JSON_OBJECT('featureFlags', JSON_OBJECT('pos.cashier_weighed_toggle', true))
        )
END
WHERE deleted_at IS NULL;
