package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Answers "what left the store room?" for a window.
 *
 * @param summary counts and totals across the returned movements — see the note on
 *                {@code StoreRoomMovementService.activity} about page capping
 */
public record StoreRoomActivityResponse(
        Instant from,
        Instant to,
        Summary summary,
        List<StoreRoomMovementResponse> movements
) {

    public record Summary(
            int total,
            int takeOuts,
            int putIns,
            /** Total quantity that actually left the shop (Class B take-outs). */
            BigDecimal stockLossQuantity
    ) {
    }
}
