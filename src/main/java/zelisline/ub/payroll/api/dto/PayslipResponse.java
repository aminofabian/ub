package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PayslipResponse(
        String id,
        String payslipNumber,
        String staffProfileId,
        String userId,
        String displayName,
        int periodYear,
        int periodMonth,
        BigDecimal baseSalary,
        BigDecimal advancesDeducted,
        BigDecimal otherDeductions,
        BigDecimal payeDeducted,
        BigDecimal nssfDeducted,
        BigDecimal shifDeducted,
        BigDecimal housingLevyDeducted,
        BigDecimal netPaid,
        Instant paidAt,
        String note,
        String expenseId,
        String paymentMethod
) {
}
