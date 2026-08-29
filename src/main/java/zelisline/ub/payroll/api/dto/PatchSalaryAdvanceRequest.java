package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

public record PatchSalaryAdvanceRequest(
        @Size(max = 32) String repaymentMode,
        @DecimalMin("0.00") BigDecimal repaymentValue,
        @Size(max = 500) String note
) {
}
