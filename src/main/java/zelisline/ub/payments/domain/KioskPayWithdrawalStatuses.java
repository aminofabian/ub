package zelisline.ub.payments.domain;

/** Withdraw request statuses. */
public final class KioskPayWithdrawalStatuses {

    public static final String REQUESTED = "REQUESTED";
    public static final String PROCESSING = "PROCESSING";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILED = "FAILED";

    private KioskPayWithdrawalStatuses() {
    }
}
