package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record ItemSupplierLinkResponse(
        String id,
        String supplierId,
        String supplierName,
        boolean primary,
        String supplierSku,
        BigDecimal defaultCostPrice,
        BigDecimal lastCostPrice,
        BigDecimal packSize,
        String packUnit,
        boolean active,
        Instant lastPurchaseAt,
        long version,
        Instant createdAt,
        Instant updatedAt,
        /** Offered pack shapes for this link (item defaults merged with link offers). */
        List<ItemPackOfferPreview> packs
) {
    public record ItemPackOfferPreview(
            String id,
            String label,
            String packUnit,
            BigDecimal unitsPerPack,
            /** Price for ONE pack; null = ask. */
            BigDecimal unitPrice,
            /** Derived unitPrice / unitsPerPack for display; null when unitPrice is null. */
            BigDecimal eachPrice
    ) {
    }
}
