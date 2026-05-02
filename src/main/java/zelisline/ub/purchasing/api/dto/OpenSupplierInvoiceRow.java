package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record OpenSupplierInvoiceRow(
        String id,
        String supplierId,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal grandTotal,
        BigDecimal openBalance
) {
}
