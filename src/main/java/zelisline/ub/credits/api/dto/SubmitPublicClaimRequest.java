package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record SubmitPublicClaimRequest(@NotNull @Positive BigDecimal amount, String reference) {
}
