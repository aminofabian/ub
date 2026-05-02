package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PostGrnSupplierInvoiceLineInput(
        @NotBlank String itemId,
        @NotNull @Positive BigDecimal qty,
        @NotNull @Positive BigDecimal unitCost,
        @NotNull BigDecimal lineTotal
) {
}
