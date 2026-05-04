package zelisline.ub.integrations.privacy.api.dto;

import jakarta.validation.constraints.NotBlank;

/** Request body for {@code POST /api/v1/integrations/privacy/exports}. */
public record PrivacyExportCreateRequest(
        @NotBlank String subjectType,
        @NotBlank String subjectId
) {}
