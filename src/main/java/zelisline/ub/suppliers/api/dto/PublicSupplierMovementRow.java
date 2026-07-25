package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PublicSupplierMovementRow(
        String description,
        BigDecimal quantity,
        BigDecimal unitCost,
        BigDecimal lineTotal,
        LocalDate invoiceDate,
        String invoiceNumber
) {
}
