package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record RecurringDrawoutItemResponse(
        String id,
        String businessId,
        String name,
        String category,
        BigDecimal defaultAmount,
        BigDecimal amountTolerance,
        String defaultDescription,
        String defaultRecipient,
        String frequency,
        Integer maxPerShift,
        boolean requiresApproval,
        boolean isActive,
        String createdBy,
        String createdByName,
        Instant createdAt,
        Instant updatedAt
) {
}
