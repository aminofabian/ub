package zelisline.ub.finance.api.dto;

import java.time.LocalDate;

public record ProcessExpenseSchedulesResponse(
        LocalDate date,
        int postedCount
) {
}
