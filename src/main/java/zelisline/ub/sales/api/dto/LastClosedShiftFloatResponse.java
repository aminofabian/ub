package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Closing float from the most recently closed shift at a branch,
 * used to optionally prefill the next opening count.
 */
public record LastClosedShiftFloatResponse(
        String shiftId,
        String branchId,
        Instant closedAt,
        BigDecimal countedClosingCash,
        List<DenominationResponse> closingDenominations
) {
    public static LastClosedShiftFloatResponse empty(String branchId) {
        return new LastClosedShiftFloatResponse(
                null,
                branchId,
                null,
                null,
                List.of()
        );
    }
}
