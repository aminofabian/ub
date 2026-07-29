package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PosTillAwaitRequest(
        @NotNull @Positive BigDecimal amount,
        String phoneNumber
) {
}
