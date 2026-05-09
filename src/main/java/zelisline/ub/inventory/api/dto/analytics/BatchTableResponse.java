package zelisline.ub.inventory.api.dto.analytics;

import java.math.BigDecimal;
import java.util.List;

public record BatchTableResponse(
        List<BatchTableRow> rows,
        long total,
        int page,
        int size
) {

    public record BatchTableRow(
            String id,
            String supplyBatchId,
            String batchNumber,
            String itemId,
            String itemName,
            String itemSku,
            String categoryName,
            String branchId,
            String branchName,
            BigDecimal initialQuantity,
            BigDecimal quantityRemaining,
            BigDecimal unitCost,
            BigDecimal totalValue,
            String expiryDate,
            String status,
            String receivedAt,
            String supplierName
    ) {
    }
}
