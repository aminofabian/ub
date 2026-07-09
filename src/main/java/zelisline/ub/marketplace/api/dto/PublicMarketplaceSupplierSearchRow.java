package zelisline.ub.marketplace.api.dto;

import java.util.List;

public record PublicMarketplaceSupplierSearchRow(
        String id,
        String name,
        String slug,
        String description,
        String supplierType,
        String listedBy,
        String location,
        List<String> locations,
        int productCount,
        String contactName,
        String contactPhone,
        String contactEmail,
        String paymentMethodPreferred,
        String payoutType,
        List<String> deliveryRegions,
        List<String> categoryTags
) {
}
