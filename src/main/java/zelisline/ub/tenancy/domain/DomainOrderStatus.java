package zelisline.ub.tenancy.domain;

/** Buy-flow lifecycle for {@link DomainOrder} (P1). */
public enum DomainOrderStatus {
    QUOTED,
    AWAITING_PAYMENT,
    REGISTERING,
    OWNED,
    PROVISIONING,
    LIVE,
    FAILED
}
