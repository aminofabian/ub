package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RecentSaleRow(
        String saleId,
        /** Short sequential receipt number (POS sales); null for web orders / older sales. */
        Long receiptNo,
        Instant soldAt,
        String cashierName,
        String customerName,
        /** Primary tender: single method, or {@code split} when multiple payments. */
        String paymentMethod,
        /** Comma-separated distinct {@code sale_payments.method} values (may be null). */
        String paymentMethods,
        String itemId,
        String itemName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        BigDecimal lineTotal,
        BigDecimal profit,
        String status,
        /** {@code walk_in} for POS; {@code online_store} for storefront checkout. */
        String channel
) {
}
