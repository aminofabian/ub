package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierPortalClaimVerifyInviteRequest(
        @NotBlank @Size(max = 32) String code,
        @Size(max = 32) String phone
) {
}
