package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Take out (or put in) against a store-room row.
 *
 * @param storeItemId the store-room row the movement is against
 * @param direction   {@code out} (take out) or {@code in} (put in)
 * @param reason      a {@code StoreRoomReason} wire value; must suit the direction
 * @param quantity    how much moved
 * @param note        optional free text, required in practice for {@code other}
 * @param branchId    optional shop to post a linked movement against; the server
 *                    resolves a sensible default when omitted
 */
public record CreateStoreRoomMovementRequest(
        @NotBlank @Size(max = 36) String storeItemId,
        @NotBlank @Size(max = 8) String direction,
        @NotBlank @Size(max = 32) String reason,
        @NotNull @DecimalMin(value = "0.0001", inclusive = true) BigDecimal quantity,
        @Size(max = 255) String note,
        @Size(max = 36) String branchId
) {
}
