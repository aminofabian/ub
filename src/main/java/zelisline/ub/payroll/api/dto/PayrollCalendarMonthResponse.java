package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;

/**
 * One month in the annual payroll calendar.
 */
public record PayrollCalendarMonthResponse(
        int month,
        String status,
        int headcount,
        int paidCount,
        int pendingCount,
        int missingSalaryCount,
        int onLeaveCount,
        BigDecimal totalNetPaid
) {
}
