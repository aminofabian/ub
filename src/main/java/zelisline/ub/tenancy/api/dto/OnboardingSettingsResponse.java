package zelisline.ub.tenancy.api.dto;

import java.time.Instant;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record OnboardingSettingsResponse(
        String status,
        int step,
        Instant updatedAt,
        Instant completedAt,
        Instant dismissedAt,
        OnboardingAnswersDto answers
) {
    public static OnboardingSettingsResponse defaults() {
        return new OnboardingSettingsResponse(
                "idle",
                1,
                null,
                null,
                null,
                null
        );
    }
}
