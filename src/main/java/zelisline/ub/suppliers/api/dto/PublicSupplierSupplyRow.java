package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record PublicSupplierSupplyRow(
        String invoiceNumber,
        LocalDate invoiceDate,
        BigDecimal grandTotal,
        BigDecimal amountPaid,
        BigDecimal balanceOpen,
        String paymentStatus,
        String sourceType
) {
}
