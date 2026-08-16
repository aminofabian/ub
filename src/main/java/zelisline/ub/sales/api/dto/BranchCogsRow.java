package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record BranchCogsRow(
        String branchId,
        String branchName,
        BigDecimal cogs
) {}
