package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record PostGrnSupplierInvoiceResponse(
        String supplierInvoiceId,
        String invoiceNumber,
        String journalEntryId,
        BigDecimal grandTotal
) {
}
