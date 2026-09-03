package zelisline.ub.catalog.api.dto;

import java.math.BigDecimal;

public record ItemSupplierSpendRow(
        String supplierId,
        String supplierName,
        BigDecimal quantity,
        BigDecimal spend
) {
}
