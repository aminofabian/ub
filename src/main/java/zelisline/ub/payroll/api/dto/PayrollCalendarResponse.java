package zelisline.ub.payroll.api.dto;

import java.util.List;

public record PayrollCalendarResponse(
        int year,
        List<PayrollCalendarMonthResponse> months
) {
}
