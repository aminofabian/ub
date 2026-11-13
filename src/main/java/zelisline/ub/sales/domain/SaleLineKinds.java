package zelisline.ub.sales.domain;

import java.util.Locale;

/** What a sale (or cart) line represents. */
public final class SaleLineKinds {

    public static final String ITEM = "ITEM";
    public static final String AIRTIME = "AIRTIME";
    /** Work performed by a staff member — a haircut, a massage. No stock behind it (ADR 0014). */
    public static final String SERVICE = "SERVICE";

    private SaleLineKinds() {
    }

    public static boolean isAirtime(String raw) {
        return raw != null && AIRTIME.equalsIgnoreCase(raw.trim());
    }

    public static boolean isService(String raw) {
        return raw != null && SERVICE.equalsIgnoreCase(raw.trim());
    }

    /** True for a kind this build understands; a blank kind means {@link #ITEM}. */
    public static boolean isKnown(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String kind = raw.trim().toUpperCase(Locale.ROOT);
        return ITEM.equals(kind) || AIRTIME.equals(kind) || SERVICE.equals(kind);
    }

    public static String normalize(String raw) {
        if (isAirtime(raw)) {
            return AIRTIME;
        }
        return isService(raw) ? SERVICE : ITEM;
    }
}
