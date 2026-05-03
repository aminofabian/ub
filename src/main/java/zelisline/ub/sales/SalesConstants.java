package zelisline.ub.sales;

public final class SalesConstants {

    public static final String SHIFT_STATUS_OPEN = "open";
    public static final String SHIFT_STATUS_CLOSED = "closed";

    public static final String SALE_STATUS_COMPLETED = "completed";
    public static final String SALE_STATUS_VOIDED = "voided";
    public static final String SALE_STATUS_REFUNDED = "refunded";

    public static final String REFUND_STATUS_COMPLETED = "completed";

    public static final String PAYMENT_METHOD_CASH = "cash";
    public static final String PAYMENT_METHOD_MPESA_MANUAL = "mpesa_manual";

    public static final String STOCK_REFERENCE_TYPE_SALE = "sale";
    public static final String STOCK_REFERENCE_TYPE_SALE_VOID = "sale_void";
    public static final String STOCK_REFERENCE_TYPE_SALE_REFUND = "sale_refund";

    public static final String JOURNAL_SOURCE_SHIFT_CLOSE = "shift_close";
    public static final String JOURNAL_SOURCE_SALE = "sale";
    public static final String JOURNAL_SOURCE_SALE_VOID = "sale_void";
    public static final String JOURNAL_SOURCE_SALE_REFUND = "sale_refund";

    private SalesConstants() {
    }
}
