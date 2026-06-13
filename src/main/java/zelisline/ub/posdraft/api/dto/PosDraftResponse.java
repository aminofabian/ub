package zelisline.ub.posdraft.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record PosDraftResponse(
        String id,
        long ticketNumber,
        String status,
        String branchId,
        String clientDraftId,
        String currency,
        BigDecimal subTotal,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        long version,
        String createdBy,
        String createdByName,
        String saleId,
        Instant createdAt,
        Instant updatedAt,
        List<PosDraftLineResponse> lines
) {
}
