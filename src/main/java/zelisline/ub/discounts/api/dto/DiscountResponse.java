package zelisline.ub.discounts.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record DiscountResponse(
        String id,
        String name,
        String description,
        String kind,
        String method,
        BigDecimal value,
        String scope,
        String branchId,
        Instant startAt,
        Instant endAt,
        boolean paused,
        Instant publishedAt,
        int priority,
        long version,
        String status,
        long affectedCount,
        List<String> itemIds,
        List<String> categoryIds,
        List<String> supplierIds,
        boolean includeAnyLinkedSupplier,
        List<String> excludedItemIds,
        Instant createdAt,
        Instant updatedAt
) {
}
