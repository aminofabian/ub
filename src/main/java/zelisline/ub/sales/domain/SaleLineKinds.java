package zelisline.ub.sales.domain;

import java.util.Locale;

/** What a sale (or cart) line represents. */
public final class SaleLineKinds {

    public static final String ITEM = "ITEM";
    public static final String AIRTIME = "AIRTIME";
    /** Work performed by a staff member — a haircut, a massage. No stock behind it (ADR 0014). */
    public static final String SERVICE = "SERVICE";
    /**
     * A dish made to order from a restaurant menu. No item, no batch and no stock movement; the
     * price comes from the menu and the chosen modifiers, never from the client alone.
     */
    public static final String DISH = "DISH";
    /**
     * Something added to a restaurant bill that is not food: a service charge, packaging, a
     * delivery fee. No item and no stock. Only the server adds one, when a bill is settled.
     */
    public static final String CHARGE = "CHARGE";
    /**
     * A night (or nights) in a lodging room (ADR 0028). No item, no batch and no stock movement;
     * the price is the room's nightly rate, frozen on the line at check-in. Posts to its own
     * {@code ROOM_REVENUE} account, not to goods or services.
     */
    public static final String ROOM = "ROOM";

    private SaleLineKinds() {
    }

    public static boolean isAirtime(String raw) {
        return raw != null && AIRTIME.equalsIgnoreCase(raw.trim());
    }

    public static boolean isDish(String raw) {
        return raw != null && DISH.equalsIgnoreCase(raw.trim());
    }

    public static boolean isCharge(String raw) {
        return raw != null && CHARGE.equalsIgnoreCase(raw.trim());
    }

    public static boolean isService(String raw) {
        return raw != null && SERVICE.equalsIgnoreCase(raw.trim());
    }

    public static boolean isRoom(String raw) {
        return raw != null && ROOM.equalsIgnoreCase(raw.trim());
    }

    /** True for a kind this build understands; a blank kind means {@link #ITEM}. */
    public static boolean isKnown(String raw) {
        if (raw == null || raw.isBlank()) {
            return true;
        }
        String kind = raw.trim().toUpperCase(Locale.ROOT);
        return ITEM.equals(kind) || AIRTIME.equals(kind) || SERVICE.equals(kind) || DISH.equals(kind)
                || CHARGE.equals(kind) || ROOM.equals(kind);
    }

    public static String normalize(String raw) {
        if (isAirtime(raw)) {
            return AIRTIME;
        }
        if (isDish(raw)) {
            return DISH;
        }
        if (isCharge(raw)) {
            return CHARGE;
        }
        if (isRoom(raw)) {
            return ROOM;
        }
        return isService(raw) ? SERVICE : ITEM;
    }
}
