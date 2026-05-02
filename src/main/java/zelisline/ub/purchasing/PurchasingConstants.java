package zelisline.ub.purchasing;

public final class PurchasingConstants {

    public static final String BATCH_SOURCE_PATH_B = "path_b_breakdown";
    public static final String BATCH_SOURCE_PATH_A_GRN = "path_a_grn";
    public static final String JOURNAL_SOURCE_PATH_B = "path_b_purchase";
    public static final String JOURNAL_SOURCE_PATH_A_GRN = "path_a_grn";
    public static final String JOURNAL_SOURCE_PATH_A_INVOICE = "path_a_invoice";
    public static final String STOCK_REF_RAW_LINE = "raw_purchase_line";
    public static final String STOCK_REF_GRN_LINE = "goods_receipt_line";
    public static final String MOVEMENT_RECEIPT = "receipt";
    public static final String MOVEMENT_WASTAGE = "wastage";

    public static final String SESSION_DRAFT = "draft";
    public static final String SESSION_POSTED = "posted";
    public static final String SESSION_CANCELLED = "cancelled";

    public static final String PO_DRAFT = "draft";
    public static final String PO_SENT = "sent";
    public static final String PO_CANCELLED = "cancelled";

    public static final String GRN_DRAFT = "draft";
    public static final String GRN_POSTED = "posted";

    public static final String LINE_PENDING = "pending";
    public static final String LINE_POSTED = "posted";

    public static final String INVOICE_POSTED = "posted";

    public static final String THREE_WAY_OFF = "off";
    public static final String THREE_WAY_WARN = "warn";
    public static final String THREE_WAY_BLOCK = "block";

    public static final String PAYMENT_POSTED = "posted";
    public static final String PAY_METHOD_CASH = "cash";
    public static final String PAY_METHOD_BANK = "bank";
    public static final String PAY_METHOD_MPESA = "mpesa";

    public static final String JOURNAL_SOURCE_SUPPLIER_PAYMENT = "supplier_payment";

    private PurchasingConstants() {
    }
}
