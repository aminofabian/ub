package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.util.List;

public record PublicSupplierPortalResponse(
        String supplierName,
        String supplierSlug,
        String shopName,
        String currency,
        BigDecimal openBalance,
        BigDecimal totalSpent,
        BigDecimal totalPaid,
        /** Unapplied advance / overpayment credit held for this supplier. */
        BigDecimal advanceCredit,
        int invoiceCount,
        List<PublicSupplierSupplyRow> supplies,
        List<PublicSupplierMovementRow> movements,
        List<String> linkedProducts,
        /**
         * True when the figures were withheld: the viewer has not unlocked the page with its PIN and
         * is not a signed-in supplier this shop connected. The zeros are then not real balances.
         */
        boolean restricted
) {
}
