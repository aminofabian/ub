package zelisline.ub.grocery.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PutGroceryDraftLineRequest(
        @NotNull @Positive BigDecimal quantity,
        @NotNull @Positive BigDecimal unitPrice,
        String unitName,
        BigDecimal discountAmount,
        Long expectedVersion
) {
    public PutGroceryDraftLineRequest {
        if (unitName == null || unitName.isBlank()) {
            unitName = "each";
        }
        if (discountAmount == null) {
            discountAmount = BigDecimal.ZERO;
        }
    }
}
