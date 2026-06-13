package zelisline.ub.posdraft;

public final class PosDraftConstants {

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String AUDIT_CREATE_DRAFT = "CREATE_DRAFT";
    public static final String AUDIT_ADD_LINE = "ADD_LINE";
    public static final String AUDIT_UPDATE_LINE = "UPDATE_LINE";
    public static final String AUDIT_REMOVE_LINE = "REMOVE_LINE";
    public static final String AUDIT_CANCEL = "CANCEL";
    public static final String AUDIT_COMPLETE = "COMPLETE";

    public static final int DEFAULT_LIST_HOURS = 48;
    public static final int MONEY_SCALE = 2;
    public static final int QTY_SCALE = 4;
    public static final int PRICE_SCALE = 4;

    private PosDraftConstants() {
    }
}
