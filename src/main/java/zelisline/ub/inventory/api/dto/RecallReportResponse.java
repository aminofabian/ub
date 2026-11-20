package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything known about one manufacturer lot: where stock still is, and which till sales and
 * online orders drew on it. Contact details are included so customers can be reached; the
 * endpoint is permission-gated for that reason.
 */
public record RecallReportResponse(
        String lotNumber,
        List<BatchSafetyRow> batches,
        BigDecimal quantityOnSale,
        BigDecimal quantityQuarantined,
        List<SaleLine> sales,
        List<OnlineOrderLine> onlineOrders,
        boolean truncated
) {

    public record SaleLine(
            String saleId,
            Long receiptNo,
            String saleStatus,
            Instant soldAt,
            String branchId,
            String itemId,
            String itemName,
            BigDecimal quantity,
            String customerId,
            String customerName
    ) {
    }

    public record OnlineOrderLine(
            String orderId,
            Instant at,
            String itemId,
            String itemName,
            BigDecimal quantity,
            String customerName,
            String customerPhone
    ) {
    }
}
