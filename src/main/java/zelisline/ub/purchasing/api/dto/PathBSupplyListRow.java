package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Listed Path B supplier receipt (posted invoice tied to a raw purchase session).
 */
public record PathBSupplyListRow(
        String supplierInvoiceId,
        String supplierId,
        String supplierName,
        String invoiceNumber,
        Instant createdAt,
        int lineCount,
        BigDecimal grandTotal,
        BigDecimal amountPaid,
        BigDecimal balanceOpen,
        /** {@code PAID}, {@code PARTIAL}, or {@code UNPAID} */
        String paymentStatus,
        /** Receiving branch from the underlying Path B session. */
        String branchId
) {
}
