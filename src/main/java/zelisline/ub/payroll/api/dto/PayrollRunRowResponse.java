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
        BigDecimal baseSalary,
        BigDecimal advancesOutstanding,
        BigDecimal suggestedNet,
        boolean alreadyPaid,
        String payslipId,
        Instant paidAt
) {
}
