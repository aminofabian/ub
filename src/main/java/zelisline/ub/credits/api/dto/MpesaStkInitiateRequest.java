package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record MpesaStkInitiateRequest(@NotBlank String customerId, @NotNull @Positive BigDecimal amount) {
}
