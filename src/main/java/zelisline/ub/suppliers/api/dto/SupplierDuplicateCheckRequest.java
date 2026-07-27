package zelisline.ub.suppliers.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

public record SupplierDuplicateCheckRequest(
        /** Combined free-text (name / phone / S-number). Preferred for cashier UX. */
        @Size(max = 255) String query,
        @Size(max = 255) String name,
        @Size(max = 32) String phone,
        @Email @Size(max = 255) String email,
        @Size(max = 64) String taxId,
        @Size(max = 32) String supplierNumber
) {
    public boolean hasAnyKey() {
        return (query != null && !query.isBlank())
                || (name != null && !name.isBlank())
                || (phone != null && !phone.isBlank())
                || (email != null && !email.isBlank())
                || (taxId != null && !taxId.isBlank())
                || (supplierNumber != null && !supplierNumber.isBlank());
    }
}
