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
        /** When set, this catalog row is a variant/pack of another product. */
        String variantOfItemId,
        /** Display name of the parent product when {@link #variantOfItemId} is set. */
        String parentItemName,
        /** Variant option label (e.g. Medium, Pink) when this row is a variant. */
        String variantName,
        boolean packageVariant,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
