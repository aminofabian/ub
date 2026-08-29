package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One row in the shop-wide salary advance ledger. */
public record PayrollAdvanceLedgerRowResponse(
        String id,
        String staffProfileId,
        String userId,
        String displayName,
        String branchName,
        BigDecimal amount,
        BigDecimal amountRepaid,
        BigDecimal balanceOutstanding,
        LocalDate advancedOn,
        String note,
        String status,
        String repaidInPayslipId,
        Instant createdAt
) {
}
