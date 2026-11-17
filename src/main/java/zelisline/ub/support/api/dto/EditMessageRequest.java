package zelisline.ub.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** New body for an edited message. */
public record EditMessageRequest(
        @NotBlank @Size(max = 4000) String body
) {}
