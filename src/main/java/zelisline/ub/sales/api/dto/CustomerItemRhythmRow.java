package zelisline.ub.sales.api.dto;

import java.time.Instant;

public record CustomerItemRhythmRow(
        String itemId,
        String itemName,
        String itemSku,
        long purchaseCount,
        Integer medianGapDays,
        Instant lastPurchaseAt,
        int daysSinceLast,
        boolean dueish,
        String summary
) {
}
