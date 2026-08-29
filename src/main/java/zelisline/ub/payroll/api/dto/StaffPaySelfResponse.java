package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only payroll portal for the logged-in staff member.
 * Omits HR fields (bank details, national ID, emergency contacts).
 */
public record StaffPaySelfResponse(
        String displayName,
        String title,
        String employmentStatus,
        LocalDate startDate,
        String phone,
        String shopName,
        BigDecimal currentSalary,
        BigDecimal advancesOutstanding,
        List<StaffPaySelfAdvanceRow> advances,
        List<StaffPaySelfPayslipRow> payslips,
        String sharePath
) {
}
