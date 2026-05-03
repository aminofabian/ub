package zelisline.ub.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;

public record PostStartStockTakeSessionRequest(
        @NotBlank String branchId,
        String notes
) {
}
