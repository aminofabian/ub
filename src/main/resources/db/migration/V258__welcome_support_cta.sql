-- Welcome in-app CTA opens the floating Support chat drawer (not a page route).
UPDATE notification_templates
SET action_url_template = 'kiosk:support-chat'
WHERE type = 'account.welcome';
