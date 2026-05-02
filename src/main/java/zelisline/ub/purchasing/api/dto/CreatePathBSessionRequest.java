package zelisline.ub.purchasing.api.dto;

import java.time.Instant;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreatePathBSessionRequest(
        @NotBlank @Size(max = 36) String supplierId,
        @NotBlank @Size(max = 36) String branchId,
        @NotNull Instant receivedAt,
        @Size(max = 5000) String notes
) {
}
