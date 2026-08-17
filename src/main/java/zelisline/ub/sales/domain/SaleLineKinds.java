package zelisline.ub.sales.domain;

/** What a sale (or cart) line represents. */
public final class SaleLineKinds {

    public static final String ITEM = "ITEM";
    public static final String AIRTIME = "AIRTIME";

    private SaleLineKinds() {
    }

    public static boolean isAirtime(String raw) {
        return raw != null && AIRTIME.equalsIgnoreCase(raw.trim());
    }

    public static String normalize(String raw) {
        return isAirtime(raw) ? AIRTIME : ITEM;
    }
}
