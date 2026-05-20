package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PosStkPushRequest(
        @NotBlank String phoneNumber,
        @NotNull @Positive BigDecimal amount,
        String description
) {
}
