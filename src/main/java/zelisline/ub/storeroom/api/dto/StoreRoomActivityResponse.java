package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Answers "what left the store room?" for a window.
 *
 * <p>The {@code summary} and {@code facets} describe the whole window; {@code movements}
 * is the filtered view. That way the headline does not shift while somebody narrows
 * the list, and the filter options stay truthful.
 */
public record StoreRoomActivityResponse(
        Instant from,
        Instant to,
        Summary summary,
        Facets facets,
        List<StoreRoomMovementResponse> movements
) {

    public record Summary(
            int total,
            int takeOuts,
            int putIns,
            /** Applied quantity that actually left the shop (Class B, not pending). */
            BigDecimal stockLossQuantity,
            /** Waiting on an approval decision — stock has NOT moved for these. */
            int pending
    ) {
    }

    /** Filter options actually present in the window. */
    public record Facets(
            List<ActorFacet> actors,
            List<ReasonFacet> reasons
    ) {
    }

    public record ActorFacet(String userId, String name, int count) {
    }

    public record ReasonFacet(String reason, int count) {
    }
}
