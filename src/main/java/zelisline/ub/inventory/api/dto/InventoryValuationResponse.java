package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record InventoryValuationResponse(
        String businessId,
        List<BranchValuationLine> byBranch,
        BigDecimal totalExtensionValue
) {
}
