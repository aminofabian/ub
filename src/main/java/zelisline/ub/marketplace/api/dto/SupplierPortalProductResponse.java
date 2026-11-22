package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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
        String imageUrl,
        /** False while the price is one copied from a shop and not yet saved by the supplier. */
        boolean pricePublic,
        /** Every pack the product is sold in, cheapest first; the first is the main price. */
        List<Pack> packs
) {
    public record Pack(
            String id,
            BigDecimal packSize,
            String packUnit,
            BigDecimal unitPrice,
            String currency
    ) {
    }
}
