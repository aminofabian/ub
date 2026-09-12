package zelisline.ub.marketplace.domain;

/** Why an escrow hold was released to the supplier. */
public final class MarketplaceEscrowReleaseTriggers {

    public static final String PO_DELIVERED = "PO_DELIVERED";
    public static final String INVOICE_APPROVED = "INVOICE_APPROVED";
    public static final String MANUAL_SA = "MANUAL_SA";
    public static final String MANUAL_TENANT = "MANUAL_TENANT";

    private MarketplaceEscrowReleaseTriggers() {
    }
}
