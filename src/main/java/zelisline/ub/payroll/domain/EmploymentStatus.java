package zelisline.ub.payroll.domain;

/**
 * Employment display status — separate from login {@code users.status}.
 */
public final class EmploymentStatus {

    public static final String ACTIVE = "active";
    public static final String ON_LEAVE = "on_leave";
    public static final String TERMINATED = "terminated";

    private EmploymentStatus() {
    }

    public static boolean isValid(String value) {
        return ACTIVE.equals(value) || ON_LEAVE.equals(value) || TERMINATED.equals(value);
    }
}
