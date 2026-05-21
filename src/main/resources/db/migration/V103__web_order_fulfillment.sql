-- Web order pickup/fulfillment lifecycle (separate from payment status).

ALTER TABLE web_orders
  ADD COLUMN fulfillment_status VARCHAR(24) NULL AFTER status;

UPDATE web_orders
   SET fulfillment_status = 'awaiting_confirmation'
 WHERE status = 'paid'
   AND (fulfillment_status IS NULL OR fulfillment_status = '');

INSERT INTO notification_templates (
  id, business_id, type, locale, version,
  title_template, body_template, action_url_template,
  notification_class, category, default_channels, active
) VALUES
  ('aaaaaaaa-0001-0000-0000-000000000014', NULL, 'order.confirmed', 'en', 1,
   'Order confirmed', 'Your order {{orderId}} is confirmed and being prepared.',
   '/shop/account', 'TRANSACTIONAL', 'orders', '["IN_APP","WEB_PUSH"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000015', NULL, 'order.dispatched', 'en', 1,
   'Ready for pickup', 'Order {{orderId}} is ready — come collect when convenient.',
   '/shop/account', 'TRANSACTIONAL', 'orders', '["IN_APP","WEB_PUSH"]', TRUE),
  ('aaaaaaaa-0001-0000-0000-000000000016', NULL, 'order.delivered', 'en', 1,
   'Order complete', 'Order {{orderId}} is marked complete. Thank you!',
   '/shop/account', 'TRANSACTIONAL', 'orders', '["IN_APP","WEB_PUSH"]', TRUE);
