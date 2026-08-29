package zelisline.ub.finance.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record ExpenseScheduleOccurrenceResponse(
        String id,
        String scheduleId,
        String scheduleName,
        String branchId,
        LocalDate occurrenceDate,
        String status,
        BigDecimal amount,
        String paymentMethod,
        String expenseId,
        Instant postedAt,
        String failureReason
) {
}
