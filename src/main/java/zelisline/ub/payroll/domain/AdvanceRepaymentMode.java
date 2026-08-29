package zelisline.ub.payroll.domain;

public final class AdvanceRepaymentMode {

    /** Deduct as much as the pay pool allows until this advance is cleared. */
    public static final String FULL_BALANCE = "full_balance";

    /** Deduct a percentage of the original advance amount each pay run. */
    public static final String PERCENT_OF_ORIGINAL = "percent_of_original";

    /** Deduct a fixed KES amount each pay run. */
    public static final String FIXED_PER_PAY = "fixed_per_pay";

    /** Skip automatic deduction — set amount when marking paid. */
    public static final String MANUAL = "manual";

    private AdvanceRepaymentMode() {
    }
}
