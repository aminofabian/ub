package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Correct an item's cost. Rewrites the unit cost on all active on-hand batches (optionally scoped
 * to {@code branchId}) and the item's reference {@code buying_price}. When {@code sellPrice} is
 * supplied it also updates the shelf / open selling price.
 */
public record AdjustItemCostRequest(
        @NotNull @DecimalMin(value = "0.0001", message = "Unit cost must be greater than zero")
        BigDecimal unitCost,

        @DecimalMin(value = "0.0", message = "Sell price cannot be negative")
        BigDecimal sellPrice,

        String branchId,

        @Size(max = 500) String reason
) {
}
