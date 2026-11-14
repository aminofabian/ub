package zelisline.ub.posdraft;

public final class PosDraftConstants {

    /** A held retail cart — every row before service tickets existed. */
    public static final String KIND_CART = "CART";
    /** A walk-in service ticket (ADR 0014). */
    public static final String KIND_SERVICE = "SERVICE";

    /** An appointment that has not arrived yet. */
    public static final String STAGE_BOOKED = "BOOKED";
    /** A cancelled appointment the customer did not turn up for. */
    /** A redo that has been done; closed without a sale. */
    public static final String STAGE_REDO_DONE = "REDO_DONE";
    /** A service of a visit that is finished (its line stage; the visit may still be going on). */
    public static final String LINE_DONE = "DONE";
    public static final String DEPOSIT_HELD = "HELD";
    public static final String DEPOSIT_APPLIED = "APPLIED";
    public static final String DEPOSIT_CREDITED = "CREDITED";
    public static final String DEPOSIT_FORFEITED = "FORFEITED";
    public static final String STAGE_NO_SHOW = "NO_SHOW";
    public static final String STAGE_WAITING = "WAITING";
    public static final String STAGE_IN_SERVICE = "IN_SERVICE";
    public static final String STAGE_READY_TO_PAY = "READY_TO_PAY";
    public static final String STAGE_ABANDONED = "ABANDONED";

    public static final String STATUS_PENDING = "pending";
    public static final String STATUS_COMPLETED = "completed";
    public static final String STATUS_CANCELLED = "cancelled";

    public static final String AUDIT_CREATE_DRAFT = "CREATE_DRAFT";
    public static final String AUDIT_ADD_LINE = "ADD_LINE";
    public static final String AUDIT_UPDATE_LINE = "UPDATE_LINE";
    public static final String AUDIT_REMOVE_LINE = "REMOVE_LINE";
    public static final String AUDIT_CANCEL = "CANCEL";
    public static final String AUDIT_COMPLETE = "COMPLETE";
    /** Bulk cancel when the owning cash-drawer shift is closed. */
    public static final String AUDIT_CANCEL_SHIFT_CLOSE = "CANCEL_SHIFT_CLOSE";

    public static final int DEFAULT_LIST_HOURS = 48;
    public static final int MONEY_SCALE = 2;
    public static final int QTY_SCALE = 4;
    public static final int PRICE_SCALE = 4;

    private PosDraftConstants() {
    }
}
