package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PathBSupplyInvoiceDetailDto(
        String supplierInvoiceId,
        String supplierId,
        String supplierName,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        String notes,
        Instant createdAt,
        BigDecimal grandTotal,
        BigDecimal amountPaid,
        BigDecimal balanceOpen,
        String paymentStatus,
        /** Branch the Path B session was received into (for shelf-price updates). */
        String branchId,
        /** Linked supply batch header when present (for extra costs). */
        String supplyBatchId,
        List<PathBSupplyExpenseDto> expenses,
        List<PathBSupplyInvoiceLineDto> lines
) {
}
