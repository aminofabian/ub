package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplierItemLinkResponse(
        String id,
        String itemId,
        String itemName,
        String sku,
        String barcode,
        BigDecimal currentStock,
        boolean primary,
        String supplierSku,
        BigDecimal defaultCostPrice,
        BigDecimal lastCostPrice,
        /** Catalog item buying price — used when link cost fields are unset. */
        BigDecimal catalogBuyingPrice,
        /** Catalog bundle/shelf price — draft retail when no open selling price is available. */
        BigDecimal catalogShelfPrice,
        BigDecimal packSize,
        String packUnit,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
