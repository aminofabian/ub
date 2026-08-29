package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PayRunRequest(
        @NotNull @Min(2000) @Max(2100) Integer year,
        @NotNull @Min(1) @Max(12) Integer month,
        BigDecimal otherDeductions,
        @Size(max = 500) String note,
        Boolean applyStatutory,
        Boolean postExpense,
        @Size(max = 32) String paymentMethod,
        @Size(max = 36) String branchId
) {
    public boolean applyStatutory() {
        return Boolean.TRUE.equals(applyStatutory);
    }

    public boolean postExpense() {
        return Boolean.TRUE.equals(postExpense);
    }
}
