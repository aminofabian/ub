package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record PublicTabStkRequest(
        @NotNull @Positive BigDecimal amount,
        /** Optional M-Pesa number to prompt (defaults to account primary phone). */
        @Size(max = 24) String phone
) {
}
