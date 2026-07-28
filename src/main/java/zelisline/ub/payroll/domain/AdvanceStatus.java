package zelisline.ub.payroll.domain;

public final class AdvanceStatus {

    public static final String OUTSTANDING = "outstanding";
    public static final String REPAID = "repaid";

    private AdvanceStatus() {
    }

    public static boolean isValid(String value) {
        return OUTSTANDING.equals(value) || REPAID.equals(value);
    }
}
