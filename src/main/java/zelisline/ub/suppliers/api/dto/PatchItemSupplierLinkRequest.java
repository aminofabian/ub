package zelisline.ub.suppliers.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

public record PatchItemSupplierLinkRequest(
        @Size(max = 191) String supplierSku,
        BigDecimal defaultCostPrice,
        /** Stock units per one {@link #packUnit()} from this supplier (e.g. 25 kg per crate). */
        BigDecimal packSize,
        @Size(max = 32) String packUnit
) {
}
