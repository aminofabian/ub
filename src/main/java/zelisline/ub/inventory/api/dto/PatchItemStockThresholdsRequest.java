package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;

/**
 * Grocery / counter edit of catalog min, reorder, and order-up-to (par) levels
 * without full {@code catalog.items.write}.
 */
public record PatchItemStockThresholdsRequest(
        @DecimalMin(value = "0", inclusive = true) BigDecimal minStockLevel,
        @DecimalMin(value = "0", inclusive = true) BigDecimal reorderLevel,
        @DecimalMin(value = "0", inclusive = true) BigDecimal reorderQty
) {
}
