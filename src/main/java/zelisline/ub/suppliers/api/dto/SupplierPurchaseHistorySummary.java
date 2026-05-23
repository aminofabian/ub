package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupplierPurchaseHistorySummary(
        BigDecimal totalSpent,
        BigDecimal totalPaid,
        BigDecimal openBalance,
        int invoiceCount,
        LocalDate lastInvoiceDate
) {
}
