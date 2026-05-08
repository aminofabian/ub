package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

public record PatchItemSupplierLinkRequest(
        @Size(max = 191) String supplierSku,
        BigDecimal defaultCostPrice
) {
}
