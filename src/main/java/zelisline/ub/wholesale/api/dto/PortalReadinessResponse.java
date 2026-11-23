package zelisline.ub.wholesale.api.dto;

import java.util.List;

/**
 * The readiness checklist for a supplier in the portal, including one with no shop (decision D2). A step is
 * done, to do, or "after_shop": it only means something once the supplier has opened a shop, so it is shown as
 * what comes next and never as a failure.
 */
public record PortalReadinessResponse(
        boolean enabled,
        List<Step> steps
) {

    public static final String DONE = "done";
    public static final String TODO = "todo";
    public static final String AFTER_SHOP = "after_shop";

    public record Step(String key, String label, String state, String actionUrl) {
    }

    public static PortalReadinessResponse off() {
        return new PortalReadinessResponse(false, List.of());
    }
}
