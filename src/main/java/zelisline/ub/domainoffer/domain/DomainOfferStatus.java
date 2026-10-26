package zelisline.ub.domainoffer.domain;

/** Lifecycle for a custom-domain + theme offer order. */
public enum DomainOfferStatus {
    /** Enrolled by a campaign; touches are still running. */
    INVITED,
    /** M-Pesa Express prompt is open — do not send more touches until it settles. */
    AWAITING_PAYMENT,
    /** KES 1,000 received; the 24-hour fulfilment clock is running. */
    PAID,
    /** Ops has picked it up. */
    IN_PROGRESS,
    /** Domain connected (or handed over) and theme work signed off. */
    DELIVERED,
    CANCELLED,
    EXPIRED,
    REFUNDED;

    public boolean isPaidLike() {
        return this == PAID || this == IN_PROGRESS || this == DELIVERED;
    }

    public boolean isClosed() {
        return this == DELIVERED || this == CANCELLED || this == EXPIRED || this == REFUNDED;
    }
}
