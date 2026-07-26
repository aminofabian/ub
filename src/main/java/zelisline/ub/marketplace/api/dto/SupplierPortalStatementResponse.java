package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record SupplierPortalStatementResponse(
        String localSupplierId,
        String shopName,
        String currency,
        int year,
        int month,
        LocalDate periodStart,
        LocalDate periodEnd,
        BigDecimal openingBalance,
        BigDecimal closingBalance,
        BigDecimal periodInvoices,
        BigDecimal periodPayments,
        List<SupplierPortalLedgerEntry> entries
) {
}
