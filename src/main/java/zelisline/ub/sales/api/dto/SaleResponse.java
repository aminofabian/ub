package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record SaleResponse(
        String id,
        /** Short sequential receipt number per business (1, 2, 3, ...). */
        Long receiptNo,
        String branchId,
        String customerId,
        String shiftId,
        String status,
        BigDecimal grandTotal,
        /** Cash handed over (full cash sales); null otherwise. */
        BigDecimal cashReceived,
        BigDecimal refundedTotal,
        String journalEntryId,
        List<SalePaymentResponse> payments,
        List<SaleItemResponse> items,
        Instant voidedAt,
        String voidedBy,
        String soldByName,
        String voidJournalEntryId,
        String voidNotes
) {
}
