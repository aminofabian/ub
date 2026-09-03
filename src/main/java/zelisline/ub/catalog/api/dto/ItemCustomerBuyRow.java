package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ItemCustomerBuyRow(
        String customerId,
        String customerName,
        BigDecimal quantity,
        BigDecimal spend,
        long saleCount,
        Instant lastSoldAt
) {
}
