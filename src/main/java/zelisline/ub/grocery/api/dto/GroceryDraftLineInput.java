package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record GroceryDraftLineInput(
        String lineId,
        @NotNull String itemId,
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal unitPrice,
        String unitName,
        BigDecimal discountAmount
) {
    public GroceryDraftLineInput {
        if (unitName == null || unitName.isBlank()) {
            unitName = "each";
        }
        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }
    }
}
