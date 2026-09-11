package zelisline.ub.credits.api.dto;

import java.time.Instant;
import java.util.List;

public record LastSaleSummaryResponse(
        Instant soldAt,
        List<String> itemNames,
        int itemCount,
        String hint
) {
    public static LastSaleSummaryResponse empty() {
        return new LastSaleSummaryResponse(null, List.of(), 0, null);
    }
}
