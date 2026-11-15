package zelisline.ub.payroll.application;

import java.math.BigDecimal;

/**
 * A staff member's commission plan, as other modules see it. {@code type} is {@code PERCENT},
 * {@code FIXED} or {@code null} (no commission on services).
 */
public record StaffCommissionPlan(
        String staffProfileId,
        String userId,
        String displayName,
        boolean bookable,
        boolean current,
        String type,
        BigDecimal rate,
        BigDecimal productRate,
        /** Their own rates on particular services, as stored (see {@code StaffServiceRates}); may be null. */
        String serviceRates
) {
}
