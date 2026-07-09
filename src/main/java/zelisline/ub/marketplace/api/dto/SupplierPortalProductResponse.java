package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplierPortalProductResponse(
        String id,
        String name,
        String barcode,
        String sku,
        String categoryName,
        String description,
        BigDecimal packSize,
        String packUnit,
        BigDecimal minOrderQty,
        BigDecimal unitPrice,
        String currency,
        boolean available,
        String status,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
