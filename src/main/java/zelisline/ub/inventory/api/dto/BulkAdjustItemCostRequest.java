package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Apply a target margin to many items at once. Sell price is kept fixed; unit cost becomes
 * {@code sell × (1 − marginPct/100)}.
 */
public record BulkAdjustItemCostRequest(
        @NotEmpty
        @Size(max = 500, message = "At most 500 items can be adjusted at once")
        List<String> itemIds,

        @NotNull
        @DecimalMin(value = "0.0", message = "Margin must be at least 0%")
        @DecimalMax(value = "99.99", message = "Margin must be less than 100%")
        BigDecimal marginPct,

        String branchId,

        @Size(max = 500) String reason
) {
}
