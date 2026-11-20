package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.Size;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record PostGoodsReceiptLineInput(
        @NotBlank String purchaseOrderLineId,
        @NotNull @Positive BigDecimal qtyReceived,
        /** Manufacturer's lot number; required when the item is a medicine. */
        @Size(max = 64) String lotNumber,
        /** Expiry date printed on the pack; required when the item is a medicine. */
        LocalDate expiryDate
) {

    public PostGoodsReceiptLineInput(String purchaseOrderLineId, BigDecimal qtyReceived) {
        this(purchaseOrderLineId, qtyReceived, null, null);
    }
}
