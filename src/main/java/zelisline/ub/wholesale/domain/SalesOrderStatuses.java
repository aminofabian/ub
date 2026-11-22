package zelisline.ub.wholesale.domain;

/** Where a trade order stands (ADR 0040). */
public final class SalesOrderStatuses {

    public static final String PLACED = "placed";
    public static final String ACCEPTED = "accepted";
    public static final String PART_ACCEPTED = "part_accepted";
    public static final String REJECTED = "rejected";
    public static final String DISPATCHED = "dispatched";
    public static final String CANCELLED = "cancelled";

    public static final String LINE_PENDING = "pending";
    public static final String LINE_ACCEPTED = "accepted";
    public static final String LINE_REJECTED = "rejected";

    public static final String SOURCE_STOREFRONT = "storefront";
    /** Taken by a clerk at the supplier's counter and put on the buyer's account. */
    public static final String SOURCE_COUNTER = "counter";

    public static final String TERMS_PREPAID = "prepaid";
    public static final String TERMS_CREDIT = "credit";

    /** {@code stock_movements.reference_type} for stock an accepted order took. */
    public static final String STOCK_REFERENCE_TYPE = "sales_order";

    private SalesOrderStatuses() {
    }
}
