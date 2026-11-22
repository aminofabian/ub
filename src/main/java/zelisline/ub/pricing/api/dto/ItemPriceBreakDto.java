package zelisline.ub.pricing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** "From {@code minQty} upwards, each one costs {@code unitPrice}." */
public record ItemPriceBreakDto(
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal minQty,
        @NotNull @DecimalMin(value = "0.01") BigDecimal unitPrice
) {
}
