package zelisline.ub.airtime.domain;

/** Where an airtime order was raised from. */
public final class AirtimeChannels {

    /** Cashier till — customer pays cash or M-Pesa at the counter. */
    public static final String POS = "POS";
    /** Public storefront — shopper pays online before dispatch. */
    public static final String STOREFRONT = "STOREFRONT";
    /** Owner selling from the dashboard. */
    public static final String DASHBOARD = "DASHBOARD";

    private AirtimeChannels() {
    }

    public static boolean isKnown(String channel) {
        return POS.equals(channel) || STOREFRONT.equals(channel) || DASHBOARD.equals(channel);
    }
}
