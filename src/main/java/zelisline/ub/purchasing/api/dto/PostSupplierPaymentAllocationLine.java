package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record PostSupplierPaymentAllocationLine(
        @NotBlank String supplierInvoiceId,
        @NotNull BigDecimal amount
) {
}
