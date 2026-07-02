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
        @Size(max = 32) String purchaseUnit
) {
}
