package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record MarketplaceSupplierDetailResponse(
        String id,
        String name,
        String slug,
        String description,
        String supplierType,
        String listedBy,
        String location,
        List<String> locations,
        String status,
        String contactEmail,
        String contactPhone,
        List<MarketplaceContactPreview> contacts,
        String paymentMethodPreferred,
        String paymentDetails,
        String payoutType,
        String payoutPhone,
        Integer creditTermsDays,
        List<String> deliveryRegions,
        List<String> categoryTags,
        List<MarketplaceCatalogProductPreview> products
) {
    public record MarketplaceContactPreview(
            String name,
            String roleLabel,
            String phone,
            String email,
            boolean primaryContact
    ) {
    }

    public record MarketplacePackOptionPreview(
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

    public record MarketplaceCatalogProductPreview(
            String id,
            String name,
            String slug,
            String barcode,
            String sku,
            String categoryName,
            String imageUrl,
            BigDecimal packSize,
            String packUnit,
            BigDecimal minOrderQty,
            BigDecimal unitPrice,
            String currency,
            boolean available,
            /** Catalog item id (for parent grouping). */
            String itemId,
            /** Parent catalog item id when this row is a variant. */
            String variantOfItemId,
            /** Display name of the parent product when this row is a variant. */
            String parentItemName,
            /** Thumbnail for the parent product (or this item when it is the parent). */
            String parentImageUrl,
            /** Purchasable pack shapes; empty means unit-only. */
            List<MarketplacePackOptionPreview> packs
    ) {
    }
}
