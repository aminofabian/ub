package zelisline.ub.finance.api.dto;

import java.time.LocalDate;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record SkipProfitPocketDayRequest(
        @NotNull LocalDate date,
        String branchId,
        @Size(max = 240) String reason
) {
}
