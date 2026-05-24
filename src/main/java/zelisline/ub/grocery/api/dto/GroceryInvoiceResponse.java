package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record GroceryInvoiceResponse(
        String id,
        String barcodeCode,
        String status,
        String branchId,
        BigDecimal subtotal,
        BigDecimal grandTotal,
        List<GroceryInvoiceLineResponse> lines,
        String notes,
        Instant expiresAt,
        String createdBy,
        String createdByName,
        Instant createdAt,
        String cancelledBy,
        String cancelledByName,
        Instant cancelledAt,
        String cancelledReason,
        String paidBy,
        String paidByName,
        Instant paidAt,
        String saleId,
        String lockedBy,
        String lockedByName,
        Instant lockedAt,
        Instant lockExpiresAt
) {
}
