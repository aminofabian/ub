package zelisline.ub.tenancy.domain;

/**
 * Lifecycle state for a {@link Business} (a.k.a. tenant). Drives both the
 * public host-resolve payload and the {@code DomainBusinessResolverFilter}
 * auth-time gate. Only {@link #ACTIVE} permits authenticated traffic; the
 * other states render branded status pages on the storefront and respond with
 * {@code 423 Locked} for authenticated APIs.
 */
public enum TenantStatus {
    ACTIVE,
    SUSPENDED,
    INACTIVE
}
