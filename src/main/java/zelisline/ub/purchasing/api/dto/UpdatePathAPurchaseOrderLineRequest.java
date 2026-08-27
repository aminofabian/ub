package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Positive;

public record UpdatePathAPurchaseOrderLineRequest(
        @Positive BigDecimal qtyOrdered,
        @Positive BigDecimal unitEstimatedCost
) {
}
