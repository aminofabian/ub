package zelisline.ub.suppliers.api.dto;

import jakarta.validation.constraints.Size;

public record PatchSupplierContactRequest(
        @Size(max = 255) String name,
        @Size(max = 128) String roleLabel,
        @Size(max = 64) String phone,
        @Size(max = 255) String email,
        Boolean primaryContact
) {
}
