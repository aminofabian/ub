package zelisline.ub.marketplace.api.dto;

import java.util.List;

public record SupplierPortalProfileResponse(
        String marketplaceSupplierId,
        String name,
        String username,
        String description,
        String contactEmail,
        String contactPhone,
        String status,
        List<String> deliveryRegions,
        List<String> categoryTags,
        String publicHubPath,
        List<SupplierPortalLinkedShopRow> linkedShops
) {
}
