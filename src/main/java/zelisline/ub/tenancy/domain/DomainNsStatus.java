package zelisline.ub.tenancy.domain;

/** Nameserver cutover state for a purchased domain (HostAfrica → Vercel). */
public enum DomainNsStatus {
    PENDING_USER,
    PENDING_OPS,
    ACTIVE
}