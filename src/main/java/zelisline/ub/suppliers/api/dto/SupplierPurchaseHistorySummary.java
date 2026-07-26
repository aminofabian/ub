package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupplierPurchaseHistorySummary(
        BigDecimal totalSpent,
        BigDecimal totalPaid,
        BigDecimal openBalance,
        /** Open balance on invoices that are partially paid (not a duplicate of {@link #openBalance}). */
        BigDecimal partialOpenBalance,
        int invoiceCount,
        LocalDate lastInvoiceDate
) {
}
