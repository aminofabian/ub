package zelisline.ub.messaging.api.dto;

import java.time.Instant;
import java.util.List;

/** Platform-wide SMS credit usage — Super Admin dashboard (SMS_CREDITS_SCOPE.md §11). */
public record SmsCreditUsageResponse(
        Instant cycleStartedAt,
        int totalSentThisCycle,
        int includedSentThisCycle,
        int purchasedSentThisCycle,
        int depletedCount,
        List<TopTenantRow> topTenants
) {
    public record TopTenantRow(
            String businessId,
            String name,
            String tier,
            int sentThisCycle,
            int available
    ) {
    }
}
