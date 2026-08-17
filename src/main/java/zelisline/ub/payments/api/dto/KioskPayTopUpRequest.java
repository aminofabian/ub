package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/** Merchant moving their own money into the Kiosk Pay wallet. */
public record KioskPayTopUpRequest(
        @NotNull(message = "amount is required")
        @DecimalMin(value = "1", message = "amount must be positive")
        BigDecimal amount,

        /** Defaults to the account's payout phone when omitted. */
        String phoneNumber
) {
}
