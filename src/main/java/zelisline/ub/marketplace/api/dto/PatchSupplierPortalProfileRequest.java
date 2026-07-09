package zelisline.ub.marketplace.api.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

public record PatchSupplierPortalProfileRequest(
        @Size(max = 5000) String description,
        @Size(max = 255) String contactEmail,
        @Size(max = 32) String contactPhone,
        List<@Size(max = 128) String> deliveryRegions,
        List<@Size(max = 128) String> categoryTags
) {
}
