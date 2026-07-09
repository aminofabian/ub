package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record MarketplaceSupplierDetailResponse(
        String id,
        String name,
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

    public record MarketplaceCatalogProductPreview(
            String id,
            String name,
            String barcode,
            String sku,
            String categoryName,
            String imageUrl,
            BigDecimal packSize,
            String packUnit,
            BigDecimal minOrderQty,
            BigDecimal unitPrice,
            String currency,
            boolean available
    ) {
    }
}
