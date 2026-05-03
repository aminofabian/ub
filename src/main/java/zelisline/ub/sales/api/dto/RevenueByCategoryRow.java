package zelisline.ub.sales.api.dto;

import java.math.BigDecimal;

public record RevenueByCategoryRow(
        String categoryId,
        String categoryName,
        BigDecimal netRevenue
) {}
