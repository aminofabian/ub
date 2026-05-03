package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;

public record ApproveStockAdjustmentRequest(
        @DecimalMin(value = "0.0001", inclusive = true) BigDecimal unitCost
) {
}
