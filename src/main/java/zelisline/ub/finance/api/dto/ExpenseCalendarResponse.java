package zelisline.ub.finance.api.dto;

import java.util.List;

public record ExpenseCalendarResponse(
        int year,
        List<ExpenseCalendarMonthResponse> months
) {
}
