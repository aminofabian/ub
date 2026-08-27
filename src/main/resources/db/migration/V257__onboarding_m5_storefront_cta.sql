-- Point M5 go-live in-app CTA at storefront settings (not the hub root).
UPDATE notification_templates
SET action_url_template = '/business/settings',
    body_template = 'Publish your storefront — same stock count as the till.'
WHERE type = 'onboarding.go_live' AND business_id IS NULL;
