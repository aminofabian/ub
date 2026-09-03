package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ItemPurchaseHistoryRow(
        String invoiceId,
        String invoiceNumber,
        LocalDate invoiceDate,
        String supplierId,
        String supplierName,
        String itemId,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal lineTotal,
        String status
) {
}
