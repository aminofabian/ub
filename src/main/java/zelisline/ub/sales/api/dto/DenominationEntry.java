package zelisline.ub.sales.api.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/**
 * A single denomination entry for opening or closing a shift.
 */
public record DenominationEntry(
        @NotNull Integer denomination,
        @NotNull String denominationType,
        @Min(0) int quantity
) {
}
