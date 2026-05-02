package zelisline.ub.suppliers.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.Size;

public record SupplierContactResponse(
        String id,
        String name,
        String roleLabel,
        String phone,
        String email,
        boolean primaryContact,
        Instant createdAt,
        Instant updatedAt
) {
}
