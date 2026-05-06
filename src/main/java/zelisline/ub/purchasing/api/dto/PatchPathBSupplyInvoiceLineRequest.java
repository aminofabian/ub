package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record PatchPathBSupplyInvoiceLineRequest(
        @NotBlank String supplierInvoiceLineId,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal usableQty,
        @NotNull @DecimalMin(value = "0.0", inclusive = true) BigDecimal wastageQty,
        @NotNull @DecimalMin(value = "0.01", inclusive = true) BigDecimal lineTotal,
        @Size(max = 2000) String description
) {
}
