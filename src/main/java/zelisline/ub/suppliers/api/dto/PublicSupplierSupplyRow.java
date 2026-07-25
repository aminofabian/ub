package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record PublicSupplierSupplyRow(
        String invoiceNumber,
        LocalDate invoiceDate,
        BigDecimal grandTotal,
        BigDecimal amountPaid,
        BigDecimal balanceOpen,
        String paymentStatus,
        String sourceType,
        List<PublicSupplierSupplyLine> lines
) {
}
