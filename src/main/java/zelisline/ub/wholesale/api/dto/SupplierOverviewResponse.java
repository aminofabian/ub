package zelisline.ub.wholesale.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * A supplier's overview on the trade desk: the one next thing, what is waiting, the readiness steps, which
 * products a buyer can order, milestones, repeat buyers and the signed-in person's own contribution. A part that
 * is switched off is null or empty.
 */
public record SupplierOverviewResponse(
        boolean enabled,
        NextThing nextThing,
        long waitingToAnswer,
        Instant oldestWaitingSince,
        List<SupplierReadinessResponse.Step> steps,
        OrderableSummary products,
        List<Milestone> milestones,
        Long repeatBuyers,
        Contribution contribution,
        List<String> recoveryGoals
) {

    /** One plain sentence and where it leads. */
    public record NextThing(String text, String url) {
    }

    /**
     * {@code total} leaves out medicines that may only be sold at the counter; they are counted in
     * {@code soldAtCounterOnly} and are never a failure. Photo and pack size are hints, not gates.
     */
    public record OrderableSummary(
            int total, int orderable, int notOrderable, int orderableWithoutPhoto, int packsWithoutSize,
            int soldAtCounterOnly, List<OrderableProduct> close) {
    }

    /** {@code missing}: price, online, stock. */
    public record OrderableProduct(String itemId, String name, List<String> missing) {
    }

    /** {@code seen} is false until the supplier dismisses the note: it is shown once. */
    public record Milestone(String key, String label, Instant achievedAt, boolean seen) {
    }

    /** The signed-in person's own work. Never a total across people. */
    public record Contribution(long ordersAnsweredWithinAWorkingDay, long productsMadeOrderable) {
    }

    public static SupplierOverviewResponse off() {
        return new SupplierOverviewResponse(false, null, 0, null, List.of(), null, List.of(), null, null, List.of());
    }
}
