package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record GlobalSupplierHubShopCard(
        String businessId,
        String shopName,
        String slugHost,
        String localSupplierId,
        BigDecimal owed,
        BigDecimal paid,
        BigDecimal pending,
        LocalDate lastSupplyAt,
        String tenantPortalPath
) {
}
