package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

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
        BigDecimal baseSalary,
        BigDecimal advancesOutstanding,
        BigDecimal statutoryTotal,
        BigDecimal payeSuggested,
        BigDecimal nssfSuggested,
        BigDecimal shifSuggested,
        BigDecimal housingLevySuggested,
        /** Total advance deduction scheduled this run from repayment arrangements (excludes manual). */
        BigDecimal advancesScheduledThisRun,
        BigDecimal suggestedNet,
        boolean alreadyPaid,
        String payslipId,
        Instant paidAt
) {
}
