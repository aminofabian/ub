package zelisline.ub.airtime.domain;

/** How the shopper paid the shop for airtime. The wallet still funds the telco. */
public final class AirtimeTenders {

    /** Cashier collected notes and coins at the counter. */
    public static final String CASH = "CASH";
    /** Shopper paid via M-Pesa STK before dispatch. */
    public static final String MPESA = "MPESA";
    /** Charged to the customer's tab. */
    public static final String TAB = "TAB";

    private AirtimeTenders() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return CASH;
        }
        String t = raw.trim().toUpperCase();
        if (CASH.equals(t) || MPESA.equals(t) || TAB.equals(t)) {
            return t;
        }
        return CASH;
    }
}
