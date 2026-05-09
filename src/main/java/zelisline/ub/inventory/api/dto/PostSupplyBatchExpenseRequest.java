package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PostSupplyBatchExpenseRequest(
        @NotBlank @Size(max = 32) String category,
        @NotNull @Positive BigDecimal amount,
        @Size(max = 500) String description
) {
}
