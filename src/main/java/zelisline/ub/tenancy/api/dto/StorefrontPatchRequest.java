package zelisline.ub.tenancy.api.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

public record StorefrontPatchRequest(
        Boolean enabled,
        @Size(max = 36) String catalogBranchId,
        @Size(max = 64) String label,
        @Size(max = 500) String announcement,
        @Size(max = 12) List<@Size(max = 36) String> featuredItemIds
) {
}
