package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

public record BranchValuationLine(
        String branchId,
        String branchName,
        BigDecimal extensionValue
) {
}
