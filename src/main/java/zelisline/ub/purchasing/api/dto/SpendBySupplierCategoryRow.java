package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record SpendBySupplierCategoryRow(
        String supplierId,
        String supplierName,
        String categoryId,
        String categoryName,
        BigDecimal spendTotal
) {
}
