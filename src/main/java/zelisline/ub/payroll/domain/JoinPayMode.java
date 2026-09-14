package zelisline.ub.payroll.domain;

/**
 * How the join month is paid once salaries unlock on the 25th.
 */
public final class JoinPayMode {

    public static final String FULL = "full";
    public static final String HALF = "half";
    public static final String PRORATE = "prorate";

    private JoinPayMode() {
    }

    public static String normalize(String raw) {
        if (raw == null || raw.isBlank()) {
            return HALF;
        }
        return switch (raw.trim().toLowerCase()) {
            case FULL -> FULL;
            case HALF -> HALF;
            case PRORATE -> PRORATE;
            default -> throw new IllegalArgumentException("joinPayMode must be full, half, or prorate");
        };
    }

    public static boolean isValid(String raw) {
        if (raw == null || raw.isBlank()) {
            return false;
        }
        String v = raw.trim().toLowerCase();
        return FULL.equals(v) || HALF.equals(v) || PRORATE.equals(v);
    }

    /** Map legacy boolean: prorate on → prorate, off → full. */
    public static String fromLegacyProrateFlag(boolean prorateJoinMonth) {
        return prorateJoinMonth ? PRORATE : FULL;
    }
}
