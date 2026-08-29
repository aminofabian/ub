package zelisline.ub.onboarding.progress.api.dto;

public record SetupProgressSubMilestoneDto(
        String key,
        String label,
        int points,
        boolean completed
) {
}
