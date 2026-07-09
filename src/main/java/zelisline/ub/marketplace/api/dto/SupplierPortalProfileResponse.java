package zelisline.ub.marketplace.api.dto;

import java.util.List;

public record SupplierPortalProfileResponse(
        String marketplaceSupplierId,
        String name,
        String description,
        String contactEmail,
        String contactPhone,
        String status,
        List<String> deliveryRegions,
        List<String> categoryTags
) {
}
