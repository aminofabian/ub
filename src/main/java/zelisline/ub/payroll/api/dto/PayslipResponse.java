package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PayslipResponse(
        String id,
        String staffProfileId,
        String userId,
        String displayName,
        int periodYear,
        int periodMonth,
        BigDecimal baseSalary,
        BigDecimal advancesDeducted,
        BigDecimal otherDeductions,
        BigDecimal netPaid,
        Instant paidAt,
        String note,
        String expenseId
) {
}
