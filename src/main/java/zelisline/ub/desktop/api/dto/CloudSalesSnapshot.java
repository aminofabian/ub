package zelisline.ub.desktop.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Cloud → till sales pull ("down" direction of desktop sync).
 *
 * <p>The till polls this periodically and upserts sales made in the web
 * portal / other tills into its local database, so the shop owner sees every
 * sale at the till (and in local reports) regardless of where it was made.
 * Idempotent by sale id — the till skips sales it already has (including the
 * ones it uploaded itself).
 */
public record CloudSalesSnapshot(List<CloudSaleData> sales) {

    public record CloudSaleData(
            String id,
            String branchId,
            String shiftId,
            Instant shiftOpenedAt,
            String status,
            String idempotencyKey,
            BigDecimal grandTotal,
            BigDecimal cashReceived,
            String soldBy,
            Instant soldAt,
            Instant voidedAt,
            String voidNotes,
            BigDecimal refundedTotal,
            Long receiptNo,
            List<CloudSaleItemData> items,
            List<CloudSalePaymentData> payments
    ) {}

    public record CloudSaleItemData(
            String id,
            int lineIndex,
            String lineKind,
            String lineLabel,
            String itemId,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal unitCost,
            BigDecimal costTotal,
            BigDecimal profit,
            BigDecimal regularUnitPrice,
            BigDecimal discountAmount,
            String discountId,
            String discountName
    ) {}

    public record CloudSalePaymentData(
            String id,
            String method,
            BigDecimal amount,
            String reference,
            int sortOrder
    ) {}
}
