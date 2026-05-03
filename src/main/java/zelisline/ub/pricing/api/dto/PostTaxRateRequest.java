package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostTaxRateRequest(
        @NotBlank String name,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal ratePercent,
        boolean inclusive,
        boolean active
) {
}
