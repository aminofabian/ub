package zelisline.ub.storefront.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PatchCheckoutDeliveryRequest(
        @Size(max = 120) String county,
        @NotBlank @Size(max = 120) String subCounty,
        @NotBlank @Size(max = 120) String ward,
        @NotBlank @Size(max = 500) String streetAddress,
        @Size(max = 1000) String deliveryNotes,
        boolean saveForNextTime
) {}
