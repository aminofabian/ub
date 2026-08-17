package zelisline.ub.kplc.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PublicKplcDepletionResponse(
        Instant estimatedEmptyAt,
        BigDecimal remainingUnits,
        BigDecimal lastPurchaseUnits,
        BigDecimal dailyUseUnits,
        int sampleIntervals,
        boolean alreadyEmpty,
        boolean alertsEnabled
) {
}
