package zelisline.ub.suppliers.api.dto;

import java.util.List;

public record SupplierPurchaseHistoryResponse(
        SupplierPurchaseHistorySummary summary,
        List<SupplierPurchaseHistoryRow> orders
) {
}
