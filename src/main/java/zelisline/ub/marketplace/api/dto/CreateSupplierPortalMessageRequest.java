package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateSupplierPortalMessageRequest(
        @NotBlank @Size(max = 36) String localSupplierId,
        @NotBlank @Size(max = 4000) String body
) {
}
