package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record TopUpWalletRequest(@NotNull @Positive BigDecimal amount) {
}
