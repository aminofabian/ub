package zelisline.ub.marketplace.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SupplierPortalClaimCompleteRequest(
        @NotBlank @Size(max = 32) String phone,
        @NotBlank @Size(min = 32, max = 128) String setupToken,
        /** Password (min length from platform settings). Omit when {@code pin} is set. */
        @Size(max = 128) String password,
        /** 4–6 digit PIN. Omit when {@code password} is set. */
        @Size(min = 4, max = 6) String pin,
        @Size(max = 255) String name,
        @Size(max = 191) String email,
        @Size(min = 2, max = 64) String username,
        /** Extra WhatsApp / call number, distinct from the verified listing phone. */
        @Size(max = 32) String altPhone,
        @Size(max = 255) String location
) {
}
