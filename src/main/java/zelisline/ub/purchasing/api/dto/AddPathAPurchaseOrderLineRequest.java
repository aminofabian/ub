package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record AddPathAPurchaseOrderLineRequest(
        @NotBlank String itemId,
        /** Base stock units ordered (the client expands packs before posting). */
        @NotNull @Positive BigDecimal qtyOrdered,
        /** Cost per base stock unit. */
        @NotNull @Positive BigDecimal unitEstimatedCost,
        /** Optional packaging as ordered: unit name ("carton", "bale", "sack"…). */
        @Size(max = 32) String purchaseUnit,
        /** Number of purchase units; {@code × unitsPerPurchaseUnit} must equal qtyOrdered. */
        @DecimalMin(value = "0.0001", message = "purchaseQty must be positive") BigDecimal purchaseQty,
        /** Base items per purchase unit. */
        @DecimalMin(value = "0.0001", message = "unitsPerPurchaseUnit must be positive") BigDecimal unitsPerPurchaseUnit,
        /** Price of one purchase unit; derived from unitEstimatedCost when absent. */
        @DecimalMin("0") BigDecimal packPrice,
        /** Saved item pack option. */
        @Size(max = 36) String packOptionId
) {

    public AddPathAPurchaseOrderLineRequest(
            String itemId,
            BigDecimal qtyOrdered,
            BigDecimal unitEstimatedCost
    ) {
        this(itemId, qtyOrdered, unitEstimatedCost, null, null, null, null, null);
    }
}
