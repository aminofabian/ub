package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

public record BatchAllocationLine(
        String batchId,
        BigDecimal quantity,
        BigDecimal unitCost
) {
}
