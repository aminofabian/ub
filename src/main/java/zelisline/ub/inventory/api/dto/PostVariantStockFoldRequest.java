package zelisline.ub.inventory.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Fold a family option's own on-hand stock into its base product's shared pool
 * (PACKAGE_FLOW_GAPS G1/G4/G7 repair). Pack rows convert by {@code packagingUnitQty}.
 */
public record PostVariantStockFoldRequest(
        @NotBlank String itemId,
        @Size(max = 2000) String notes
) {
}
