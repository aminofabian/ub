package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateRecurringItemRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank String category,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal defaultAmount,
        @DecimalMin(value = "0", inclusive = true) BigDecimal amountTolerance,
        @Size(max = 300) String defaultDescription,
        @Size(max = 255) String defaultRecipient,
        String frequency,
        Integer maxPerShift,
        boolean requiresApproval
) {
}
