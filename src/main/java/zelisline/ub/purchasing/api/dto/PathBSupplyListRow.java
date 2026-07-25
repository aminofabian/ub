package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Listed supplier receipt (posted invoice from Path B receive or Path A order confirm / GRN).
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
        /** Receiving branch from the Path B session or Path A goods receipt. */
        String branchId
) {
}
