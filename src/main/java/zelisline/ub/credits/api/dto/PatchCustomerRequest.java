package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;
import java.util.List;

import jakarta.validation.constraints.Size;

public record PatchCustomerRequest(
        @Size(max = 500) String name,
        @Size(max = 255) String email,
        @Size(max = 10_000) String notes,
        /** Null = leave tags unchanged; empty list = clear all tags. */
        @Size(max = 16) List<@Size(max = 64) String> tags,
        BigDecimal creditLimit,
        Boolean creditSuspended,
        Long version,
        Long creditAccountVersion
) {
}
