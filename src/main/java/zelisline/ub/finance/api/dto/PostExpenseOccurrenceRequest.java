package zelisline.ub.finance.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostExpenseOccurrenceRequest(
        @NotBlank String scheduleId,
        @NotNull LocalDate occurrenceDate
) {
}
