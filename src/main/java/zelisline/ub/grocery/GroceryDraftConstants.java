package zelisline.ub.grocery;

public final class GroceryDraftConstants {

    public static final String STATUS_BUILDING = "building";
    public static final String STATUS_ISSUED = "issued";
    public static final String STATUS_CANCELLED = "cancelled";
    public static final String STATUS_ISSUE_FAILED = "issue_failed";

    public static final String AUDIT_CREATE_DRAFT = "CREATE_DRAFT";
    public static final String AUDIT_ADD_LINE = "ADD_LINE";
    public static final String AUDIT_UPDATE_LINE = "UPDATE_LINE";
    public static final String AUDIT_REMOVE_LINE = "REMOVE_LINE";
    public static final String AUDIT_ISSUE = "ISSUE";
    public static final String AUDIT_ISSUE_FAILED = "ISSUE_FAILED";
    public static final String AUDIT_CANCEL = "CANCEL";
    public static final String AUDIT_REFRESH_PRICES = "REFRESH_PRICES";

    public static final int DEFAULT_LIST_HOURS = 48;
    public static final int DEFAULT_MAX_AGE_HOURS = 72;
    public static final int MONEY_SCALE = 2;
    public static final int QTY_SCALE = 4;
    public static final int PRICE_SCALE = 4;

    private GroceryDraftConstants() {
    }
}
