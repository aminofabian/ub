package zelisline.ub.marketplace.domain;

/** Status for {@link MarketplaceEscrowHold}. */
public final class MarketplaceEscrowHoldStatuses {

    public static final String HELD = "HELD";
    public static final String RELEASE_QUEUED = "RELEASE_QUEUED";
    public static final String SETTLING = "SETTLING";
    public static final String SETTLED = "SETTLED";
    public static final String RELEASED_TO_SHOP = "RELEASED_TO_SHOP";
    public static final String CANCELLED = "CANCELLED";
    public static final String FAILED = "FAILED";

    private MarketplaceEscrowHoldStatuses() {
    }
}
