package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record WebOrderSummaryResponse(
        String id,
        String status,
        BigDecimal grandTotal,
        String currency,
        String customerName,
        String customerPhone,
        String catalogBranchId,
        String catalogBranchName,
        Instant createdAt
) {}
