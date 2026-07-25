package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Admin/owner correction of an open shift's opening float.
 *
 * <p>When {@code denominations} are provided, opening cash is recomputed from their totals.
 * Otherwise {@code openingCash} is required.
 */
public record PatchUpdateShiftOpeningRequest(
        @DecimalMin(value = "0", inclusive = true) BigDecimal openingCash,
        @Size(max = 2000) String notes,
        @Valid List<DenominationEntry> denominations,
        @NotBlank @Size(min = 3, max = 500) String reason
) {
}
