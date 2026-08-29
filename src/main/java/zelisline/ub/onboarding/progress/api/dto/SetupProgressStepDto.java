package zelisline.ub.onboarding.progress.api.dto;

import java.util.List;

public record SetupProgressStepDto(
        String key,
        String label,
        String status,
        boolean required,
        int earnedPoints,
        int maxPoints,
        String actionUrl,
        String recommendedSubKey,
        List<SetupProgressSubMilestoneDto> subMilestones
) {
}
