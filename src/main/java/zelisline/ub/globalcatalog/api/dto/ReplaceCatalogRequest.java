package zelisline.ub.globalcatalog.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Replace the tenant product catalog with a starter pack.
 *
 * <p>Only allowed for empty shops: no sales history and no non-zero inventory batches.
 * Active items are soft-deleted, then the pack is adopted with recommended prices and no
 * opening stock.
 */
public record ReplaceCatalogRequest(
        @NotBlank @Size(max = 36) String openingBranchId,
        @NotBlank @Size(max = 36) String packId
) {
}
