package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * One-screen answer to "what does this pay run cost, what still blocks it, and
 * did the money reach finance?".
 *
 * <p>Amounts are a mix of two questions and are labelled in the UI accordingly:
 * <ul>
 *   <li><b>cost</b> — contractual gross for the period plus any unpaid prior
 *       months included in the run (what the shop owes staff),</li>
 *   <li><b>cash</b> — net payable after statutory, advances and other
 *       deductions (what actually leaves the till / bank).</li>
 * </ul>
 * Statutory money is withheld from staff but still a cost to the shop and a
 * liability to remit, so it is reported separately rather than netted away.
 */
public record PayrollSummaryResponse(
        int year,
        int month,
        /** True once the labeled month has reached its pay window (25th). */
        boolean salaryReleased,
        LocalDate unlockDate,
        int headcount,
        int paidCount,
        int pendingCount,
        int onLeaveCount,
        int missingSalaryCount,
        /** Payable base for the labeled month (proration already applied). */
        BigDecimal currentGross,
        /** Unpaid prior months rolled into this run. */
        BigDecimal arrearsGross,
        /** currentGross + arrearsGross. */
        BigDecimal gross,
        /** PAYE + NSSF + SHIF + Housing Levy withheld on the run (employee share). */
        BigDecimal statutory,
        /** Advance repayments scheduled from this month's pay. */
        BigDecimal advancesScheduled,
        /** Net still to disburse for pending staff (includes arrears). */
        BigDecimal netPayable,
        /** Net already recorded on this period's payslips. */
        BigDecimal paidNet,
        /** netPayable + paidNet — cash for this period's run. */
        BigDecimal cashRequired,
        Comparison previous,
        Finance finance,
        List<Issue> issues,
        List<BranchBreakdown> branches
) {

    /** Same period one month earlier, for the "up/down vs last month" line. */
    public record Comparison(
            int year,
            int month,
            BigDecimal gross,
            BigDecimal paidNet,
            int headcount,
            /** Percentage change in gross, null when there was no prior gross. */
            BigDecimal grossDeltaPct
    ) {
    }

    /**
     * Payroll → finance reconciliation for the labeled period. A paid payslip
     * without an expense means salary left the business but never hit the books.
     */
    public record Finance(
            int postedCount,
            int unpostedCount,
            BigDecimal postedAmount,
            BigDecimal unpostedNet
    ) {
    }

    /**
     * A grouped "before you pay" check. Severity is {@code blocker},
     * {@code warning}, or {@code info}. Staff names are capped so the payload
     * stays small; the count is always exact.
     */
    public record Issue(
            String code,
            String label,
            String detail,
            String severity,
            int count,
            List<StaffRef> staff
    ) {
    }

    public record StaffRef(String userId, String displayName) {
    }

    public record BranchBreakdown(
            String branchId,
            String branchName,
            int headcount,
            int paidCount,
            int pendingCount,
            BigDecimal gross,
            BigDecimal netPayable
    ) {
    }
}
