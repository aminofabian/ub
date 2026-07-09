package zelisline.ub.suppliers.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record SupplierDuplicateCheckRequest(
        @Size(max = 255) String name,
        @Size(max = 32) String phone,
        @Email @Size(max = 255) String email,
        @Size(max = 64) String taxId
) {
    public boolean hasAnyKey() {
        return (name != null && !name.isBlank())
                || (phone != null && !phone.isBlank())
                || (email != null && !email.isBlank())
                || (taxId != null && !taxId.isBlank());
    }
}
