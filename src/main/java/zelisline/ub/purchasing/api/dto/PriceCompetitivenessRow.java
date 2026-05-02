package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

public record PriceCompetitivenessRow(
        String supplierInvoiceLineId,
        String supplierInvoiceId,
        String invoicingSupplierId,
        String itemId,
        String itemSku,
        BigDecimal paidUnitCost,
        String primarySupplierId,
        BigDecimal primaryLastCostPrice,
        BigDecimal variancePercentVsPrimary,
        boolean purchasedFromPrimarySupplier
) {
}
