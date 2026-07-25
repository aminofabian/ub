package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierPortalClaimSendCodeRequest(
        @NotBlank @Size(max = 32) String phone
) {
}
