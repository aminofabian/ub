package zelisline.ub.credits.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

public record CustomerBulkMessageRequest(
        @NotEmpty @Size(max = 500) List<@NotBlank @Size(max = 36) String> customerIds,
        @NotBlank @Size(max = 1000) String body
) {
}
