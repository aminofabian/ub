package zelisline.ub.payroll.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record SalaryResponse(
        String id,
        String staffProfileId,
        String userId,
        BigDecimal amount,
        LocalDate effectiveFrom,
        String createdBy,
        Instant createdAt
) {
}
