package zelisline.ub.storefront.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PublicTillAwaitRequest(
        @NotNull @Positive BigDecimal amount,
        String phoneNumber
) {
}
