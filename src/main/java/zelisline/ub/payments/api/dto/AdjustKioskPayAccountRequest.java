package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Super-admin manual wallet adjustment (refund reversal, corrections). */
public record AdjustKioskPayAccountRequest(
        @NotNull(message = "delta is required") BigDecimal delta,
        @Size(max = 512, message = "note must be at most 512 characters") String note
) {
}
