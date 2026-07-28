package zelisline.ub.ai.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record AiFeedbackRequest(
        @NotBlank @Size(max = 36) String requestId,
        @NotBlank @Pattern(regexp = "up|down") String feedback
) {}
