package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PostGoodsReceiptLineInput(
        @NotBlank String purchaseOrderLineId,
        @NotNull @Positive BigDecimal qtyReceived
) {
}
