package zelisline.ub.globalcatalog.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AdoptLineRequest(
        @NotBlank String globalProductId,
        @Size(max = 191) String sku,
        @Size(max = 36) String categoryId,
        @DecimalMin(value = "0.01", inclusive = true) BigDecimal sellingPrice,
        @DecimalMin(value = "0.01", inclusive = true) BigDecimal buyingPrice,
        @DecimalMin(value = "0.0001", inclusive = true) BigDecimal openingQty,
        @DecimalMin(value = "0.0001", inclusive = true) BigDecimal openingUnitCost,
        BigDecimal reorderLevel,
        BigDecimal reorderQty,
        BigDecimal minStockLevel
) {
}
