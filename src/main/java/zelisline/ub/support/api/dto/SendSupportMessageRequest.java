package zelisline.ub.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SendSupportMessageRequest(
        @NotBlank @Size(max = 4000) String body
) {}
