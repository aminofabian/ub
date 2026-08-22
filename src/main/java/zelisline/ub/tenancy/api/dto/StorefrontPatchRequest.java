package zelisline.ub.tenancy.api.dto;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

public record StorefrontPatchRequest(
        Boolean enabled,
        @Size(max = 36) String catalogBranchId,
        @Size(max = 64) String label,
        @Size(max = 500) String announcement,
        @Size(max = 12) List<@Size(max = 36) String> featuredItemIds,
        @Size(max = 100) List<@Valid DeliveryAreaDto> deliveryAreas,
        @Size(max = 64) String storeThemeId,
        @Size(max = 64) String landingTemplateId,
        @Valid LandingContentDto landingContent,
        /**
         * Opaque versioned merchant design overrides (see frontend
         * {@code StorefrontDesign}). The backend only validates that it parses
         * as a JSON object; the storefront applies it over the theme defaults.
         */
        @Size(max = 40_000) String designJson
) {
}
