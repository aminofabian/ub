-- Weighted cart toggle for cashiers: default ON (admin can still disable in settings).
-- Ensures existing tenants get the scale button on the POS.

UPDATE businesses
SET settings = JSON_SET(
    JSON_SET(
        CASE
            WHEN settings IS NULL OR TRIM(settings) = '' THEN CAST('{}' AS JSON)
            ELSE CAST(settings AS JSON)
        END,
        '$.featureFlags',
        IFNULL(
            JSON_EXTRACT(
                CASE
                    WHEN settings IS NULL OR TRIM(settings) = '' THEN CAST('{}' AS JSON)
                    ELSE CAST(settings AS JSON)
                END,
                '$.featureFlags'
            ),
            CAST('{}' AS JSON)
        )
    ),
    '$.featureFlags."pos.cashier_weighed_toggle"',
    TRUE
)
WHERE deleted_at IS NULL;
