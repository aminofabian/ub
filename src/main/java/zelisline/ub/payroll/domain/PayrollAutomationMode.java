package zelisline.ub.payroll.domain;

public final class PayrollAutomationMode {

    /** Mark all eligible staff paid using run settings. */
    public static final String AUTO_PAY = "auto_pay";

    /** Notify owners to review and pay manually — no automatic payslips. */
    public static final String REMIND = "remind";

    private PayrollAutomationMode() {
    }
}
