package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

import jakarta.validation.constraints.Size;

public record PatchCustomerRequest(
        @Size(max = 500) String name,
        @Size(max = 255) String email,
        @Size(max = 10_000) String notes,
        BigDecimal creditLimit,
        Long version,
        Long creditAccountVersion
) {
}
