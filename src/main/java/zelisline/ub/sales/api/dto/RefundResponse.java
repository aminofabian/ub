package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record RefundResponse(
        String id,
        String saleId,
        BigDecimal totalRefunded,
        String journalEntryId,
        Instant refundedAt,
        String reason,
        List<RefundLineResponse> lines,
        List<RefundPaymentResponse> payments,
        SaleResponse sale
) {
}
