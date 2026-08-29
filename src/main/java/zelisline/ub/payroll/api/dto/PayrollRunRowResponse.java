package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One row in the monthly payroll preview / run list.
 */
public record PayrollRunRowResponse(
        String userId,
        String staffProfileId,
        String displayName,
        String title,
        String employmentStatus,
        String branchName,
        String branchId,
        /** Base salary for the selected pay period only. */
        BigDecimal baseSalary,
        /** Sum of base salaries from consecutive unpaid prior months. */
        BigDecimal arrearsBaseTotal,
        /** Unpaid prior months included in this run (oldest first). */
        List<PayrollArrearPeriodResponse> arrearPeriods,
        BigDecimal advancesOutstanding,
        /** Statutory on selected period base only. */
        BigDecimal statutoryTotal,
        BigDecimal payeSuggested,
        BigDecimal nssfSuggested,
        BigDecimal shifSuggested,
        BigDecimal housingLevySuggested,
        /** Statutory on arrears base (zero when statutory preview is off). */
        BigDecimal arrearsStatutoryTotal,
        /** Total advance deduction scheduled this run from repayment arrangements (excludes manual). */
        BigDecimal advancesScheduledThisRun,
        /** Net for selected period + arrears after statutory and scheduled advance deductions. */
        BigDecimal suggestedNet,
        boolean alreadyPaid,
        String payslipId,
        Instant paidAt
) {
}
