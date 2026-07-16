package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PaymentClaimReviewRowResponse(
        String id,
        String businessId,
        String creditAccountId,
        String customerId,
        String customerName,
        String customerPhone,
        String status,
        String source,
        String proposedChannel,
        BigDecimal submittedAmount,
        String submittedReference,
        String creditNote,
        String submittedByUserId,
        String approvedJournalId,
        String rejectionReason,
        Instant createdAt,
        Instant updatedAt
) {
}
