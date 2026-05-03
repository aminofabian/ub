package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PostSaleLineRequest(
        @NotBlank String itemId,
        @NotNull @Positive BigDecimal quantity,
        @NotNull BigDecimal unitPrice
) {
}
