package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ItemSupplierLinkResponse(
        String id,
        String supplierId,
        String supplierName,
        boolean primary,
        String supplierSku,
        BigDecimal defaultCostPrice,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
