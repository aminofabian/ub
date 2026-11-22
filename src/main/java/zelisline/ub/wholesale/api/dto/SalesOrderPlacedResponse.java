package zelisline.ub.wholesale.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** What the buyer gets back: enough to quote the order, nothing about the shop's books. */
public record SalesOrderPlacedResponse(
        String orderId,
        String orderCode,
        String status,
        BigDecimal total,
        String currency,
        Instant createdAt
) {
}
