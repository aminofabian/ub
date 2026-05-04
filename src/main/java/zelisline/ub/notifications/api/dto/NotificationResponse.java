package zelisline.ub.notifications.api.dto;

import java.time.Instant;

public record NotificationResponse(
        String id,
        String type,
        String payloadJson,
        Instant readAt,
        Instant createdAt
) {
}
