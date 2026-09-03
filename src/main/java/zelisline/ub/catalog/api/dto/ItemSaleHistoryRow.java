package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ItemSaleHistoryRow(
        String saleId,
        Long receiptNo,
        Instant soldAt,
        String branchId,
        String branchName,
        String itemId,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        BigDecimal costTotal,
        BigDecimal profit
) {
}
