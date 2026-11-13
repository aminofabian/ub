package zelisline.ub.serviceops.domain;

/** How a per-service commission override is expressed (ADR 0014). */
public final class ServiceCommissionTypes {

    /** {@code commission_value} is a percentage of the line total, 0–100. */
    public static final String PERCENT = "PERCENT";
    /** {@code commission_value} is a fixed KES amount per service performed. */
    public static final String FIXED = "FIXED";

    private ServiceCommissionTypes() {
    }

    public static boolean isKnown(String raw) {
        return PERCENT.equals(raw) || FIXED.equals(raw);
    }
}
