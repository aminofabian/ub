package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PostStockIncreaseRequest(
        @NotBlank String branchId,
        @NotBlank String itemId,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal unitCost,
        @Size(max = 2000) String notes,
        /** Manufacturer's lot number; required when the item is a medicine. */
        @Size(max = 64) String lotNumber,
        /** Expiry date printed on the pack; required when the item is a medicine. */
        LocalDate expiryDate
) {

    public PostStockIncreaseRequest(
            String branchId,
            String itemId,
            BigDecimal quantity,
            BigDecimal unitCost,
            String notes
    ) {
        this(branchId, itemId, quantity, unitCost, notes, null, null);
    }
}
