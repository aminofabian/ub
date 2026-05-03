package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record WebOrderDetailResponse(
        String id,
        String cartId,
        String status,
        BigDecimal grandTotal,
        String currency,
        String catalogBranchId,
        String catalogBranchName,
        String customerName,
        String customerPhone,
        String customerEmail,
        String notes,
        Instant createdAt,
        List<WebOrderLineSnapshotResponse> lines
) {}
