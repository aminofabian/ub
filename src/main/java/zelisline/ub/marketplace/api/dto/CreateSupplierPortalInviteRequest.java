package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.Size;

public record CreateSupplierPortalInviteRequest(
        @Size(max = 32) String phone,
        Boolean sendSms
) {
}
