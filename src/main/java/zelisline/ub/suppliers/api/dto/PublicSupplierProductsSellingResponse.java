package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record PublicSupplierProductsSellingResponse(
        String period,
        LocalDate periodStart,
        LocalDate periodEnd,
        String currency,
        String sort,
        List<PublicSupplierProductSellingRow> products
) {
    public record PublicSupplierProductSellingRow(
            String itemId,
            String name,
            String sku,
            BigDecimal unitsSold,
            BigDecimal revenue,
            BigDecimal currentStock,
            Instant lastSoldAt
    ) {
    }
}
