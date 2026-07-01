package zelisline.ub.tenancy.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/**
 * GitHub Actions callback when a tenant mobile build progresses or finishes.
 */
public record MobilePublishCallbackRequest(
        @NotBlank String slug,
        @NotBlank
        @Pattern(regexp = "building|submitted|failed")
        String status,
        String workflowUrl,
        String lastError
) {}
