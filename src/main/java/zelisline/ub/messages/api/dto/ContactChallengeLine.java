package zelisline.ub.messages.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

public record ContactChallengeLine(
        @NotNull @Min(0) @Max(20) Integer qty,
        @NotNull @Min(0) @Max(5000) Integer unitPrice
) {}
