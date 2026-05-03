package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequest(
        @NotBlank @Size(max = 500) String name,
        @Size(max = 36) String parentId,
        @Size(max = 500) String icon,
        Integer position,
        @Size(max = 10_000) String description,
        BigDecimal defaultMarkupPct,
        @Size(max = 36) String defaultTaxRateId
) {
}
