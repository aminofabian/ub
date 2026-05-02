package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AddPathBLineRequest(
        @NotBlank @Size(max = 2000) String description,
        @NotNull @Positive BigDecimal amountMoney,
        @Size(max = 36) String suggestedItemId
) {
}
