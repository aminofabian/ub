package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record GroceryInvoiceSummaryResponse(
        String id,
        String barcodeCode,
        String status,
        BigDecimal grandTotal,
        int lineCount,
        String createdBy,
        String createdByName,
        Instant createdAt,
        Instant expiresAt,
        String lockedBy,
        String lockedByName
) {
}
