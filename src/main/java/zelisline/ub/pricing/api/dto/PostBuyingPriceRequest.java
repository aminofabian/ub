package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostBuyingPriceRequest(
        @NotBlank String itemId,
        @NotBlank String supplierId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitCost,
        @NotNull LocalDate effectiveFrom,
        String sourceType,
        String notes
) {
}
