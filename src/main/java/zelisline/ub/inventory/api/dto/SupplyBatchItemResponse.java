package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

public record SupplyBatchItemResponse(
        String inventoryBatchId,
        String itemId,
        String itemName,
        String itemSku,
        String batchNumber,
        BigDecimal initialQuantity,
        BigDecimal quantityRemaining,
        BigDecimal quantitySold,
        BigDecimal quantityWasted,
        BigDecimal unitCost,
        String expiryDate,
        String status
) {
}
