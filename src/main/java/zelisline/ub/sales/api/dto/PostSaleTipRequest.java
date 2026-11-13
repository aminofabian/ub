package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** A tip taken with the sale for a staff member who performed a service on it (ADR 0014). */
public record PostSaleTipRequest(
        @NotBlank String staffProfileId,
        @NotNull @DecimalMin("0.01") @Digits(integer = 12, fraction = 2) BigDecimal amount
) {
}
