package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostSellingPriceRequest(
        @NotBlank String itemId,
        String branchId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal price,
        @NotNull LocalDate effectiveFrom,
        String notes
) {
}
