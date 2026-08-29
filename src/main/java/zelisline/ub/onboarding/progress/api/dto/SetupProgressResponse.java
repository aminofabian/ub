package zelisline.ub.onboarding.progress.api.dto;

import java.time.Instant;
import java.util.List;

public record SetupProgressResponse(
        boolean visible,
        int percentComplete,
        int earnedPoints,
        int maxPoints,
        boolean shopReady,
        String currentStepKey,
        Instant snoozedUntil,
        List<SetupProgressStepDto> steps,
        SetupProgressRewardDto reward
) {
}
