package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record AddItemSupplierLinkRequest(
        @NotBlank @Size(max = 36) String supplierId,
        @Size(max = 191) String supplierSku,
        BigDecimal defaultCostPrice,
        Boolean setPrimary
) {
}
