package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;

public record BulkPriceSideRequest(
        @NotNull BulkPriceMode mode,
        BigDecimal value,
        Boolean overwriteExisting
) {
    public BulkPriceSideRequest {
        overwriteExisting = Boolean.TRUE.equals(overwriteExisting);
    }
}
