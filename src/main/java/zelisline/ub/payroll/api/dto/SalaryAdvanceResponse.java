package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record SalaryAdvanceResponse(
        String id,
        String staffProfileId,
        String userId,
        BigDecimal amount,
        LocalDate advancedOn,
        String note,
        String status,
        String repaidInPayslipId,
        Instant createdAt
) {
}
