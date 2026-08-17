package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PublicAirtimeOrderRequest(
        /** Number that receives the airtime. */
        @NotBlank(message = "phoneNumber is required")
        String phoneNumber,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "1", message = "amount must be at least 1")
        BigDecimal amount,

        /** Number that pays, when different from the one being topped up. */
        String payerPhone,

        /** Which of the store's gateways to charge; the store default when omitted. */
        String configId
) {
}
