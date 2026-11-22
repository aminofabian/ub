package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * A supplier's own standing over a window of days (SUPPLIER_ENGAGEMENT_DESIGN.md §4.2). Supplier-only: nothing
 * here reaches a buyer. State is {@code off}, {@code new} (below the sample floor, so no measures) or
 * {@code measured}. {@code dispatchLeadWorkingDays} and {@code closedDays} are the supplier's own settings,
 * absent when off.
 */
public record SupplierStandingResponse(
        boolean enabled,
        String state,
        int answeredOrders,
        int buyers,
        List<Measure> measures,
        Integer dispatchLeadWorkingDays,
        List<String> closedDays
) {

    /** One line of standing. {@code unit} is {@code hours} or {@code percent}; {@code sample} is what it is based on. */
    public record Measure(String key, String label, BigDecimal value, String unit, int sample) {
    }

    public static SupplierStandingResponse off() {
        return new SupplierStandingResponse(false, "off", 0, 0, List.of(), null, null);
    }
}
