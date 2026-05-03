package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SaleResponse(
        String id,
        String branchId,
        String customerId,
        String shiftId,
        String status,
        BigDecimal grandTotal,
        BigDecimal refundedTotal,
        String journalEntryId,
        List<SalePaymentResponse> payments,
        List<SaleItemResponse> items,
        Instant voidedAt,
        String voidedBy,
        String voidJournalEntryId,
        String voidNotes
) {
}
