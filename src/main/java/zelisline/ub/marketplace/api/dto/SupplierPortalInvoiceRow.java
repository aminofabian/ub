package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupplierPortalInvoiceRow(
        String invoiceId,
        String businessId,
        String businessName,
        String invoiceNumber,
        LocalDate invoiceDate,
        LocalDate dueDate,
        BigDecimal subtotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        String status
) {
}
