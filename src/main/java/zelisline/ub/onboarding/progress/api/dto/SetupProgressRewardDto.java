package zelisline.ub.onboarding.progress.api.dto;

import java.time.Instant;

/**
 * One-time completion bonus when required setup steps are done.
 */
public record SetupProgressRewardDto(
        int smsCredits,
        Instant grantedAt,
        boolean justGranted
) {
}
