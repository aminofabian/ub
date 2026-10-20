-- Subscription packages: daily revenue caps + self-serve package changes.
--
-- Every published tier gets a daily revenue ceiling alongside its catalog and
-- seat caps. Owners can only move up the ladder; the backend enforces this in
-- SubscriptionRenewalService. Billing is switched on here because package
-- selection/payment is now a first-class tenant flow.

ALTER TABLE platform_subscription_plans
  ADD COLUMN daily_revenue_limit_kes DECIMAL(12,2) NULL;

UPDATE platform_subscription_plans
SET daily_revenue_limit_kes = CASE LOWER(tier_code)
      WHEN 'free'       THEN 5000.00
      WHEN 'starter'    THEN 15000.00
      WHEN 'business'   THEN 30000.00
      WHEN 'growth'     THEN 60000.00
      ELSE NULL
    END
WHERE daily_revenue_limit_kes IS NULL;

UPDATE platform_subscription_billing_settings
SET billing_enabled = TRUE
WHERE billing_enabled = FALSE;
