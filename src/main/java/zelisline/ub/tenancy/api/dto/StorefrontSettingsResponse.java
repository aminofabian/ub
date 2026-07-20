package zelisline.ub.tenancy.api.dto;

import java.util.List;

public record StorefrontSettingsResponse(
        boolean enabled,
        String catalogBranchId,
        String label,
        String announcement,
        List<String> featuredItemIds,
        List<DeliveryAreaDto> deliveryAreas
) {
    public static StorefrontSettingsResponse defaults() {
        return new StorefrontSettingsResponse(
                false,
                null,
                null,
                null,
                List.of(),
                DeliveryAreaDefaults.seed()
        );
    }
}
