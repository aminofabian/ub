package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;

/** One unpaid salary month rolled into the selected pay run as arrears. */
public record PayrollArrearPeriodResponse(
        int year,
        int month,
        BigDecimal baseSalary,
        BigDecimal statutoryTotal,
        BigDecimal payeSuggested,
        BigDecimal nssfSuggested,
        BigDecimal shifSuggested,
        BigDecimal housingLevySuggested,
        /** Base minus statutory for this arrear month (no advance deductions). */
        BigDecimal netBeforeAdvances
) {
}
