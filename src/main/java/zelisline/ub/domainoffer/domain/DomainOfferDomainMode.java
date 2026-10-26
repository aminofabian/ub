package zelisline.ub.domainoffer.domain;

/** Whether the merchant already owns the domain or needs us to register it. */
public enum DomainOfferDomainMode {
    /** Merchant owns the name; KES 1,000 covers the connection. */
    OWNED,
    /** We register a .co.ke separately (existing KES 2,000 domain order flow). */
    NEEDS_REGISTRATION
}
