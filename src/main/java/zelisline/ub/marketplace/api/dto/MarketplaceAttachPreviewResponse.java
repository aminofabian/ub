package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * What attaching a global supplier would pull into the caller's shop: the supplier's active shelf,
 * each product flagged against the caller's own catalogue. Read-only.
 */
public record MarketplaceAttachPreviewResponse(
        String marketplaceSupplierId,
        String supplierNumber,
        String supplierName,
        String contactPhone,
        String contactEmail,
        /** True when the caller's shop already has an active connection to this supplier. */
        boolean alreadyConnected,
        /** The shop's own supplier row when {@code alreadyConnected}, else null. */
        String localSupplierId,
        List<MarketplaceAttachPreviewProduct> products
) {

    public record MarketplaceAttachPreviewProduct(
            /** Marketplace product id: send these back as {@code productIds} to attach. */
            String id,
            String name,
            String barcode,
            String sku,
            String categoryName,
            BigDecimal packSize,
            String packUnit,
            BigDecimal minOrderQty,
            /** Current available price for one pack; null = ask the supplier. */
            BigDecimal unitPrice,
            String currency,
            /** The shop already stocks this barcode, so attaching links it instead of creating an item. */
            boolean inShop,
            /** Already linked to the shop's copy of this supplier. */
            boolean alreadyLinked
    ) {
    }
}
