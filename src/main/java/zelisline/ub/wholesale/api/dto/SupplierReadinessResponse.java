package zelisline.ub.wholesale.api.dto;

import java.util.List;

/**
 * The supplier readiness checklist. Every {@code done} is a server fact; the client never marks a step done.
 * When the feature is off, {@code enabled} is false and {@code steps} is empty, so nothing leaks.
 */
public record SupplierReadinessResponse(boolean enabled, List<Step> steps, List<Milestone> milestones) {

    public record Step(String key, String label, boolean done, String actionUrl) {
    }

    /** A milestone reached once, with when. Only present while the milestones flag is on. */
    public record Milestone(String key, java.time.Instant achievedAt) {
    }

    public static SupplierReadinessResponse off() {
        return new SupplierReadinessResponse(false, List.of(), List.of());
    }
}
