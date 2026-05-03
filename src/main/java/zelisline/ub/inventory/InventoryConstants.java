package zelisline.ub.inventory;

public final class InventoryConstants {

    public static final String MOVEMENT_OPENING = "opening";
    public static final String MOVEMENT_ADJUSTMENT = "adjustment";
    public static final String MOVEMENT_TRANSFER_OUT = "transfer_out";
    public static final String MOVEMENT_TRANSFER_IN = "transfer_in";
    public static final String MOVEMENT_SALE = "sale";
    public static final String MOVEMENT_SALE_VOID = "sale_void";
    public static final String MOVEMENT_REFUND = "refund";
    public static final String BATCH_SOURCE_OPENING = "opening_balance";
    public static final String BATCH_SOURCE_STOCK_GAIN = "stock_count_gain";
    public static final String REF_OPERATION = "inventory_operation";
    public static final String REF_STOCK_TRANSFER_LINE = "stock_transfer_line";
    public static final String BATCH_SOURCE_STOCK_TRANSFER = "stock_transfer";
    public static final String TRANSFER_STATUS_DRAFT = "draft";
    public static final String TRANSFER_STATUS_COMPLETED = "completed";
    public static final String STOCKTAKE_SESSION_IN_PROGRESS = "in_progress";
    public static final String STOCKTAKE_SESSION_CLOSED = "closed";
    public static final String ADJUSTMENT_REQUEST_PENDING = "pending";
    public static final String ADJUSTMENT_REQUEST_APPROVED = "approved";
    public static final String ADJUSTMENT_REQUEST_REJECTED = "rejected";
    public static final String ADJUSTMENT_TYPE_STOCK_TAKE = "stock_take";
    public static final String REASON_COUNTING_ERROR = "counting_error";
    public static final String REF_STOCK_ADJUSTMENT_REQUEST = "stock_adjustment_request";
    public static final String JOURNAL_OPENING = "inventory_opening";
    public static final String JOURNAL_COUNT_GAIN = "inventory_count_gain";
    public static final String JOURNAL_ADJUSTMENT_DOWN = "inventory_adjustment_down";
    public static final String JOURNAL_STANDALONE_WASTAGE = "inventory_wastage";
    public static final String BATCH_STATUS_ACTIVE = "active";
    public static final String BATCH_SOURCE_REFUND_RETURN = "refund_return";

    private InventoryConstants() {
    }
}
