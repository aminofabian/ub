package zelisline.ub.tenancy.api.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OnboardingPatchRequest(
        @Pattern(regexp = "idle|pending|active|completed|dismissed") String status,
        @Min(1) @Max(6) Integer step,
        @Valid OnboardingAnswersDto answers
) {
}
