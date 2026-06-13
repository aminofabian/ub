package zelisline.ub.tenancy.api.dto;

import jakarta.validation.Valid;

public record InventoryPatchRequest(
        @Valid StocktakePatchRequest stocktake,
        @Valid StockLevelsPatchRequest stockLevels
) {
}
