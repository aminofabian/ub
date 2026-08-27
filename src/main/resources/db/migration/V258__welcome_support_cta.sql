-- Welcome in-app CTA should open Support (chat), not the hub (often already open).
UPDATE notification_templates
SET action_url_template = '/support'
WHERE type = 'account.welcome'
  AND (action_url_template IS NULL
    OR action_url_template = ''
    OR action_url_template = '/business'
    OR action_url_template = '/');
