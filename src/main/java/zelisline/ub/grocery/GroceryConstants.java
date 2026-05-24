package zelisline.ub.grocery;

public final class GroceryConstants {

    public static final String STATUS_PENDING_PAYMENT = "pending_payment";
    public static final String STATUS_PAID = "paid";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_EXPIRED = "expired";

    public static final int DEFAULT_EXPIRY_HOURS = 24;

    /** Invoice lock expiry in minutes (auto-release if cashier abandons). */
    public static final int LOCK_EXPIRY_MINUTES = 5;

    public static final String BARCODE_PREFIX = "GI-";

    public static final String STOCK_REFERENCE_TYPE_GROCERY = "grocery_invoice";

    private GroceryConstants() {
    }
}
