package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record GroceryDraftResponse(
        String id,
        long counterNumber,
        String status,
        String branchId,
        String clientDraftId,
        String invoiceId,
        String notes,
        String currency,
        BigDecimal subTotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        long version,
        String createdBy,
        String createdByName,
        Instant createdAt,
        Instant updatedAt,
        Instant issuedAt,
        Instant cancelledAt,
        String cancelledReason,
        List<GroceryDraftLineResponse> lines
) {
}
