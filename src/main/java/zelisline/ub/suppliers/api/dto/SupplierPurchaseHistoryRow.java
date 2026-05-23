package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record SupplierPurchaseHistoryRow(
        String supplierInvoiceId,
        String invoiceNumber,
        LocalDate invoiceDate,
        Instant createdAt,
        int lineCount,
        BigDecimal grandTotal,
        BigDecimal amountPaid,
        BigDecimal balanceOpen,
        /** {@code PAID}, {@code PARTIAL}, or {@code UNPAID} */
        String paymentStatus,
        /** {@code DIRECT_SUPPLY}, {@code GOODS_RECEIPT}, or {@code INVOICE} */
        String sourceType
) {
}
