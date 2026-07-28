package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

public record CreateSalaryRequest(
        @NotNull @DecimalMin("0.01") BigDecimal amount,
        @NotNull LocalDate effectiveFrom
) {
}
