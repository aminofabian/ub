package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record DrawoutResponse(
        String id,
        String shiftId,
        String registerId,
        String category,
        String recurringItemId,
        BigDecimal amount,
        String description,
        String recipientName,
        String recipientContact,
        String reference,
        String status,
        int approvalTier,
        String initiatedBy,
        String initiatedByName,
        String approvedBy,
        String approvedByName,
        Instant approvedAt,
        String rejectedBy,
        String rejectedByName,
        String rejectionReason,
        String voidedBy,
        String voidedByName,
        String voidReason,
        Instant voidedAt,
        Instant expiresAt,
        Instant createdAt,
        Instant updatedAt
) {
}
