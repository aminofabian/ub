package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record SupplierPortalLedgerEntry(
        LocalDate date,
        String type,
        String reference,
        String description,
        BigDecimal debit,
        BigDecimal credit,
        BigDecimal balance
) {
}
