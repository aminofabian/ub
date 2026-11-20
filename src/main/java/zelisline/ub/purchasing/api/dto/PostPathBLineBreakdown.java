package zelisline.ub.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

public record PostPathBLineBreakdown(
        @NotBlank @Size(max = 36) String lineId,
        @NotBlank @Size(max = 36) String itemId,
        @NotNull @PositiveOrZero BigDecimal usableQty,
        @NotNull @PositiveOrZero BigDecimal wastageQty,
        /** Optional; stored on the inventory batch when {@code usableQty} &gt; 0. */
        LocalDate expiryDate,
        /** Optional purchase qty as entered by receiver — validated against supplier pack conversion. */
        @PositiveOrZero BigDecimal purchaseQty,
        @Size(max = 32) String purchaseUnit,
        /** Manufacturer's lot number; falls back to the draft line's. Required for medicines. */
        @Size(max = 64) String lotNumber
) {

    public PostPathBLineBreakdown(
            String lineId,
            String itemId,
            BigDecimal usableQty,
            BigDecimal wastageQty,
            LocalDate expiryDate,
            BigDecimal purchaseQty,
            String purchaseUnit
    ) {
        this(lineId, itemId, usableQty, wastageQty, expiryDate, purchaseQty, purchaseUnit, null);
    }
}
