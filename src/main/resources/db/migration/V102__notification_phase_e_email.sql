-- Phase E — enable EMAIL on owner insight digests (staff users with email).

UPDATE notification_templates
   SET default_channels = '["IN_APP","EMAIL"]'
 WHERE business_id IS NULL
   AND type IN (
       'insights.abandoned_cart',
       'insights.peak_hours',
       'insights.top_products',
       'sales.daily_digest'
   );
