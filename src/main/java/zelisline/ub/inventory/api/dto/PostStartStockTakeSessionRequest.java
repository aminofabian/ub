package zelisline.ub.inventory.api.dto;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Start a stock-take session. If itemIds is provided, only those items will be
 * included in the session. If not provided, falls back to checklist or all stocked items.
 */
public record PostStartStockTakeSessionRequest(
        @NotBlank String branchId,
        @NotBlank String sessionType,
        @NotNull LocalDate sessionDate,
        String notes,
        List<String> itemIds
) {
}
