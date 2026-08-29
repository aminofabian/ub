package zelisline.ub.billing.domain;

/**
 * Subscription billing lifecycle — source of truth for grace and payment suspension.
 * Distinct from manual {@link zelisline.ub.tenancy.domain.TenantStatus} support holds.
 */
public enum SubscriptionBillingStatus {
    ACTIVE,
    GRACE,
    SUSPENDED
}
