package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Create a new catalog item and immediately add it as a stock-take line in one
 * atomic transaction. This avoids orphan products if the subsequent stock-take
 * call fails.
 */
public record CreateItemWithStocktakeLineRequest(
        @NotBlank @Size(max = 500) String name,
        @Size(max = 191) String barcode,
        @Size(max = 16) String unitType,
        @NotNull @Size(max = 36) String itemTypeId,
        @Size(max = 36) String categoryId,
        @Size(max = 255) String brand,
        @Size(max = 50) String size,
        Boolean isStocked,
        Boolean isSellable,
        @NotNull @DecimalMin(value = "0", inclusive = true) BigDecimal countedQty,
        @Size(max = 255) String aisle
) {
}
