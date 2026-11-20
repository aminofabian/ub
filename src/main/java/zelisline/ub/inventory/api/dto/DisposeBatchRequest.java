package zelisline.ub.inventory.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * Writes off stock from a quarantined batch. {@code quantity} null means everything left in it.
 * {@code wastageReason} is a {@code WastageReason} name (EXPIRED, RECALLED, CUSTOMER_RETURN, ...).
 */
public record DisposeBatchRequest(
        @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @Size(max = 32) String wastageReason,
        @Size(max = 200) String note,
        /**
         * What the screen showed as left in the batch. When sent and it no longer matches, the
         * write-off is refused, so a repeated click cannot write the same stock off twice.
         */
        @DecimalMin(value = "0", inclusive = true) BigDecimal expectedRemaining
) {
    public DisposeBatchRequest(BigDecimal quantity, String wastageReason, String note) {
        this(quantity, wastageReason, note, null);
    }
}
