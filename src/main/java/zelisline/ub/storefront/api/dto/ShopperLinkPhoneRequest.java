package zelisline.ub.storefront.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ShopperLinkPhoneRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(max = 128) String phoneVerificationToken
) {
}
