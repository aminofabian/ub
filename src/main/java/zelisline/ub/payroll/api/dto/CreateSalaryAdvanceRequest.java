package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateSalaryAdvanceRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate advancedOn,
        @Size(max = 500) String note
) {
}
