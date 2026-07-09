package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record SupplierPortalShipRequest(
        @NotBlank
        @Pattern(regexp = "in_transit|delivered")
        String deliveryStatus,
        @Size(max = 2000) String trackingNote
) {
}
