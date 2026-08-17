package zelisline.ub.airtime.domain;

/** Lifecycle of an airtime order, mirroring Kiosk Pay withdrawals. */
public final class AirtimeOrderStatuses {

    /** Storefront order raised but the shopper has not paid yet — no wallet hold. */
    public static final String AWAITING_PAYMENT = "AWAITING_PAYMENT";
    /** Wallet funds held; not yet handed to the provider. */
    public static final String REQUESTED = "REQUESTED";
    /** Provider accepted the request and is talking to the telco. */
    public static final String SUBMITTED = "SUBMITTED";
    /** Provider is still waiting on the telco's final word. */
    public static final String PENDING = "PENDING";
    /** Airtime delivered; hold settled and commission credited. */
    public static final String SUCCESS = "SUCCESS";
    /** Terminal failure; hold released back to available balance. */
    public static final String FAILED = "FAILED";

    private AirtimeOrderStatuses() {
    }

    public static boolean isTerminal(String status) {
        return SUCCESS.equals(status) || FAILED.equals(status);
    }

    /** Statuses where wallet funds are held and the provider owes us an answer. */
    public static boolean isInFlight(String status) {
        return REQUESTED.equals(status) || SUBMITTED.equals(status) || PENDING.equals(status);
    }
}
