package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Reverse a mistaken tab payment and record the correct amount in one step. */
public record AmendTabPaymentRequest(
        @NotBlank @Size(max = 36) String customerId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount,
        @Size(max = 16) String channel,
        @Size(max = 128) String reference
) {
}
