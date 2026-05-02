package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record PostPathBResponse(
        String supplierInvoiceId,
        String invoiceNumber,
        String journalEntryId,
        BigDecimal grandTotal,
        int linesPosted
) {
}
