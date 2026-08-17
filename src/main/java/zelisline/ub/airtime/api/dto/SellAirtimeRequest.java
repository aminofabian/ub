package zelisline.ub.airtime.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SellAirtimeRequest(
        @NotBlank(message = "phoneNumber is required")
        String phoneNumber,

        @NotNull(message = "amount is required")
        @DecimalMin(value = "1", message = "amount must be at least 1")
        BigDecimal amount,

        /** POS or DASHBOARD; storefront orders come through the public endpoint. */
        String channel,

        String customerId,

        /** Set when the till already recorded the cash sale this airtime belongs to. */
        String saleId
) {
}
