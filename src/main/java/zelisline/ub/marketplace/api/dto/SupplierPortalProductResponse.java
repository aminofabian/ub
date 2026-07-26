package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;

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
        Instant updatedAt,
        String pendingEditId,
        Map<String, Object> pendingProposed,
        String imageUrl
) {
}
