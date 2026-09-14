package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
        /** Payable base for the selected pay period (prorated when mid-month join). */
        BigDecimal baseSalary,
        /** Full contractual monthly amount before proration; zero when no salary. */
        BigDecimal monthlySalary,
        /**
         * Payable days / days in month when prorated; null when full month or no salary.
         */
        BigDecimal prorationFactor,
        /**
         * Join-month pay mode once unlocked: full, half, or prorate.
         */
        String joinPayMode,
        /**
         * False until the 25th of the labeled month — payable base is zero until then.
         */
        boolean salaryReleased,
        /** Employment start / join date from the staff profile, if set. */
        LocalDate startDate,
        /** Effective-from date of the salary row used for {@code monthlySalary}, if any. */
        LocalDate salaryEffectiveFrom,
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
