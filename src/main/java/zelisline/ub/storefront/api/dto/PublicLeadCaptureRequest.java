package zelisline.ub.storefront.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicLeadCaptureRequest(
        @NotBlank @Size(max = 16) String areaCode,
        @NotBlank @Size(max = 64) String phone,
        @Size(max = 64) String whatsApp,
        /** Optional until the guest adds to cart / provides location. */
        @Size(max = 80) String deliveryArea,
        @Size(max = 500) String streetAddress
) {
}
