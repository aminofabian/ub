package zelisline.ub.marketplace.api.dto;

import java.util.List;

public record PublicMarketplaceSupplierSearchRow(
        String id,
        String name,
        String description,
        List<String> deliveryRegions,
        List<String> categoryTags
) {
}
