package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;

public record PublicSupplierSupplyLine(
        String description,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal lineTotal
) {
}
