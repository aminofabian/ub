package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record PostCloseShiftRequest(
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal countedClosingCash,
        String notes,
        String varianceReason,
        List<DenominationEntry> denominations
) {
}
