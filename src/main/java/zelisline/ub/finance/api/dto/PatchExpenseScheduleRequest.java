package zelisline.ub.finance.api.dto;

import java.time.LocalDate;

public record PatchExpenseScheduleRequest(
        LocalDate endDate,
        Boolean active
) {
}

