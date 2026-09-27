package zelisline.ub.sales.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Manager classification of a till drawout that the automatic rule leaves
 * till-only — posts it as operating expense under the given soft category code.
 */
public record PostDrawoutExpenseRequest(
        @NotBlank String categoryCode
) {
}
