package zelisline.ub.domainoffer.domain;

/** Whether the merchant already owns the domain or needs us to register it. */
public enum DomainOfferDomainMode {
    /** Merchant owns the name; KES 1,000 covers the connection. */
    OWNED,
    /** We register one .co.ke name; the price is included in the KES 1,000 offer. */
    NEEDS_REGISTRATION
}
