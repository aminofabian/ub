package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

/** Payslip row scoped to the authenticated staff member only. */
public record StaffPaySelfPayslipRow(
        String id,
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
        String paymentMethod
) {
}
