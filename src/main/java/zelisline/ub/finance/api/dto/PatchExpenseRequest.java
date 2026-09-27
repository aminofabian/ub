package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PatchExpenseRequest(
        @NotNull LocalDate expenseDate,
        @NotBlank String name,
        @NotNull BigDecimal amount,
        @NotBlank String paymentMethod,
        String categoryCode,
        String categoryType
) {
}
