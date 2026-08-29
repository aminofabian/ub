package zelisline.ub.payroll.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record PayAllRunRequest(
        @NotNull @Min(2000) @Max(2100) Integer year,
        @NotNull @Min(1) @Max(12) Integer month,
        Boolean applyStatutory,
        Boolean postExpense,
        String paymentMethod,
        String branchId
) {
}
