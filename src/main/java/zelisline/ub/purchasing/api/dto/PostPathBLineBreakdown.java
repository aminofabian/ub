package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PostPathBLineBreakdown(
        @NotBlank @Size(max = 36) String lineId,
        @NotBlank @Size(max = 36) String itemId,
        @NotNull @PositiveOrZero BigDecimal usableQty,
        @NotNull @PositiveOrZero BigDecimal wastageQty
) {
}
