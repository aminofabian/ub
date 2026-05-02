package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplierResponse(
        String id,
        String name,
        String code,
        String supplierType,
        String vatPin,
        boolean taxExempt,
        Integer creditTermsDays,
        BigDecimal creditLimit,
        BigDecimal rating,
        String status,
        String notes,
        String paymentMethodPreferred,
        String paymentDetails,
        long version,
        Instant createdAt,
        Instant updatedAt
) {
}
