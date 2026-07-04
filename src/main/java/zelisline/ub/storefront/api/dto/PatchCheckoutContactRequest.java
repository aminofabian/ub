package zelisline.ub.storefront.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PatchCheckoutContactRequest(
        @NotBlank @Size(max = 120) String firstName,
        @NotBlank @Size(max = 120) String lastName,
        @NotBlank @Email @Size(max = 255) String email,
        @NotBlank @Size(max = 16) String areaCode,
        @NotBlank @Size(max = 64) String phone,
        @Size(max = 64) String whatsApp
) {}
