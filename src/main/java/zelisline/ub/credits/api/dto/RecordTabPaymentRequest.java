package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Immediate tab settlement by a claims reviewer (partial or full). */
public record RecordTabPaymentRequest(
        @NotBlank @Size(max = 36) String customerId,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal amount,
        @NotBlank @Size(max = 16) String channel,
        @Size(max = 128) String reference
) {
}
