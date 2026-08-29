package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record StaffPaySelfAdvanceRow(
        String id,
        BigDecimal amount,
        BigDecimal amountRepaid,
        BigDecimal balanceOutstanding,
        LocalDate advancedOn,
        String status,
        String note,
        String repaymentMode,
        BigDecimal repaymentValue
) {
}
