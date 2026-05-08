package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RecentSaleRow(
        String saleId,
        Instant soldAt,
        String cashierName,
        String customerName,
        String paymentMethod,
        String itemId,
        String itemName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        BigDecimal profit,
        String status
) {
}
