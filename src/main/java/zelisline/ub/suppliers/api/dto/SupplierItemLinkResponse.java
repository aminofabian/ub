package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplierItemLinkResponse(
        String id,
        String itemId,
        String itemName,
        String sku,
        boolean primary,
        String supplierSku,
        BigDecimal defaultCostPrice,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
