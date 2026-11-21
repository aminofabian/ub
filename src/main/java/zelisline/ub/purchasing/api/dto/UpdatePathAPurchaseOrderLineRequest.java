package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

public record UpdatePathAPurchaseOrderLineRequest(
        /** Base stock units ordered. */
        @Positive BigDecimal qtyOrdered,
        /** Cost per base stock unit. */
        @Positive BigDecimal unitEstimatedCost,
        /** Blank clears the recorded packaging. */
        @Size(max = 32) String purchaseUnit,
        @DecimalMin(value = "0.0001", message = "purchaseQty must be positive") BigDecimal purchaseQty,
        @DecimalMin(value = "0.0001", message = "unitsPerPurchaseUnit must be positive") BigDecimal unitsPerPurchaseUnit,
        @DecimalMin("0") BigDecimal packPrice,
        @Size(max = 36) String packOptionId
) {
}
