package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record CustomerProductSegmentRow(
        String customerId,
        Long customerNo,
        String name,
        String primaryPhone,
        long purchaseCount,
        BigDecimal spendOnItem,
        Instant lastPurchaseAt
) {
}
