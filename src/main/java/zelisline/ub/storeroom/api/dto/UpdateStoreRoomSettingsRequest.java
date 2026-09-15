package zelisline.ub.storeroom.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Size;

/**
 * A partial update of the store room's settings. At least one field must be set.
 *
 * @param mode                  choose {@code standalone} or {@code connected}
 * @param approvalThreshold     ask before more than this leaves stock
 * @param clearApprovalThreshold stop asking
 */
public record UpdateStoreRoomSettingsRequest(
        @Size(max = 16) String mode,
        @DecimalMin(value = "0.0", inclusive = false) BigDecimal approvalThreshold,
        Boolean clearApprovalThreshold
) {
}
