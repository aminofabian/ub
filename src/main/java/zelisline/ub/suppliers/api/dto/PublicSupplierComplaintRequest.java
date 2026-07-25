package zelisline.ub.suppliers.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record PublicSupplierComplaintRequest(
        @Size(max = 120) String name,
        @Size(max = 32) String phone,
        @NotBlank @Size(max = 2000) String message,
        /** Honeypot — leave empty. */
        @Size(max = 120) String website
) {
}
