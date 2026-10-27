package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One sale line, reduced to what the hub's live-activity tape paints.
 *
 * <p>The tape groups lines into sales and shows the cashier, tender and total;
 * it never reads customer identity, SKU/barcode, unit price, channel or M-Pesa
 * verification. Sending those anyway made an 8-day window of lines one of the
 * largest responses on the dashboard.
 */
public record RecentSaleTapeRow(
        String saleId,
        Instant soldAt,
        String cashierName,
        /** Primary tender: single method, or {@code split} when multiple payments. */
        String paymentMethod,
        /** Comma-separated distinct tenders, so the tape can print "Cash + M-Pesa". */
        String paymentMethods,
        String itemName,
        BigDecimal quantity,
        BigDecimal lineTotal,
        /** Kept so a refunded line subtracts from the sale total, as the full row does. */
        BigDecimal profit,
        String status
) {
}
