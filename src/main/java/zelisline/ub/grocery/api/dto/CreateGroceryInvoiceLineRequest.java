package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateGroceryInvoiceLineRequest(
        @NotNull String itemId,
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal unitPrice,
        String unitName
) {
    public CreateGroceryInvoiceLineRequest {
        if (unitName == null || unitName.isBlank()) {
            unitName = "each";
        }
    }
}
