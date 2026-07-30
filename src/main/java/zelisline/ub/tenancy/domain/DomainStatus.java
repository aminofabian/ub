package zelisline.ub.tenancy.domain;

/**
 * Provisioning lifecycle for a {@link DomainMapping}.
 * Host resolve only honors rows that are {@link #ACTIVE} and {@code active=true}.
 */
public enum DomainStatus {
    PENDING,
    VERIFYING,
    ACTIVE,
    FAILED
}
