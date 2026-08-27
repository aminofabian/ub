-- Drawouts over the self-approve tier were pending forever: the approve
-- permission was never seeded, and pending amounts never left expected cash.
-- Grant approve to owner/admin/manager, count open-shift pending drawouts in
-- the till, and add the in-app approval notification template.

INSERT INTO permissions (id, permission_key, description) VALUES
  ('11111111-0000-0000-0000-000000000710', 'shifts.drawouts.approve',
   'Approve or reject cash drawouts from an open shift.')
ON DUPLICATE KEY UPDATE
  description = VALUES(description);

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE p.permission_key = 'shifts.drawouts.approve'
  AND r.role_key IN ('owner', 'admin', 'manager')
  AND r.deleted_at IS NULL
  AND NOT EXISTS (
    SELECT 1
    FROM role_permissions rp
    WHERE rp.role_id = r.id
      AND rp.permission_id = p.id
  );

-- Cash already left the drawer when the cashier recorded the drawout.
UPDATE shifts s
INNER JOIN (
  SELECT shift_id, SUM(amount) AS pending_total
  FROM cash_drawouts
  WHERE status = 'PENDING_APPROVAL'
  GROUP BY shift_id
) d ON d.shift_id = s.id
SET s.expected_closing_cash = s.expected_closing_cash - d.pending_total
WHERE s.status = 'open';

INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000050', NULL, 'drawout.approval_requested', 'en', 1,
   'Cash drawout needs approval',
   '{{initiatedByName}} took {{amount}} {{currency}} ({{category}}) for {{recipientName}}. Approve or reject before close.',
   '/shifts?drawout={{drawoutId}}', 'TRANSACTIONAL', 'cash_drawer', '["IN_APP"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000051', NULL, 'drawout.recorded', 'en', 1,
   'Cash drawout recorded',
   '{{initiatedByName}} took {{amount}} {{currency}} ({{category}}) for {{recipientName}}. Already applied to the till.',
   '/shifts?drawout={{drawoutId}}', 'OPERATIONAL', 'cash_drawer', '["IN_APP"]', TRUE)
ON DUPLICATE KEY UPDATE
  title_template = VALUES(title_template),
  body_template = VALUES(body_template),
  action_url_template = VALUES(action_url_template),
  active = TRUE;
