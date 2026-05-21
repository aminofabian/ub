package zelisline.ub.notifications.api.dto;

import java.time.Instant;

public record NotificationSubscriptionResponse(
        String id,
        String itemId,
        String kind,
        boolean active,
        Instant createdAt
) {
}
