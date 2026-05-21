package zelisline.ub.notifications.api.dto;

import java.time.Instant;

public record NotificationCampaignResponse(
        String id,
        String name,
        String campaignType,
        String status,
        String title,
        String body,
        String actionUrl,
        String recipientScope,
        String catalogBranchId,
        Instant scheduledAt,
        Instant startedAt,
        Instant completedAt,
        int recipientsTargeted,
        int recipientsSent,
        Instant createdAt
) {
}
