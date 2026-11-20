package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

/** One inventory batch as the stock-safety screens show it. */
public record BatchSafetyRow(
        String batchId,
        String itemId,
        String itemName,
        String sku,
        String branchId,
        String branchName,
        String batchNumber,
        String lotNumber,
        LocalDate expiryDate,
        BigDecimal quantityRemaining,
        BigDecimal unitCost,
        String status,
        String quarantineReason,
        Instant quarantinedAt,
        Instant receivedAt,
        boolean medicine
) {
}
