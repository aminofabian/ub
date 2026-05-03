package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostOpenShiftRequest(
        @NotBlank String branchId,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal openingCash,
        String notes
) {
}
