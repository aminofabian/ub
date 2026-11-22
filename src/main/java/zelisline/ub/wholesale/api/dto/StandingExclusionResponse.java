package zelisline.ub.wholesale.api.dto;

import java.time.Instant;

public record StandingExclusionResponse(
        String orderId,
        boolean standingExcluded,
        Instant standingExcludedAt,
        String standingExclusionNote
) {
}
