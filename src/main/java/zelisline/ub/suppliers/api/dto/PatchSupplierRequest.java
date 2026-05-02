package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

public record PatchSupplierRequest(
        @Size(max = 255) String name,
        @Size(max = 64) String code,
        @Size(max = 32) String supplierType,
        @Size(max = 64) String vatPin,
        Boolean taxExempt,
        Integer creditTermsDays,
        BigDecimal creditLimit,
        @Size(max = 16) String status,
        @Size(max = 5000) String notes,
        @Size(max = 32) String paymentMethodPreferred,
        @Size(max = 2000) String paymentDetails
) {
}
