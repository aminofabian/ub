package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

public record ItemStockThresholdsResponse(
        String itemId,
        BigDecimal minStockLevel,
        BigDecimal reorderLevel
) {
}
