-- Drawouts already land in the bell. The owner phone should hear them too.

UPDATE notification_templates
   SET default_channels = '["IN_APP","WEB_PUSH"]'
 WHERE business_id IS NULL
   AND type IN ('drawout.approval_requested', 'drawout.recorded');
