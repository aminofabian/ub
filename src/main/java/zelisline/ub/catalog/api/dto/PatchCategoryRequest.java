package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

public record PatchCategoryRequest(
        @Size(max = 500) String name,
        @Size(max = 191) String slug,
        @Size(max = 36) String parentId,
        /** When true, clears {@code parentId} (top-level). Ignores {@link #parentId} when set. */
        Boolean root,
        Boolean active,
        Integer position,
        @Size(max = 500) String icon,
        @Size(max = 10_000) String description,
        Boolean clearDescription,
        BigDecimal defaultMarkupPct,
        Boolean clearDefaultMarkup,
        @Size(max = 36) String defaultTaxRateId,
        Boolean clearDefaultTaxRate
) {
}
