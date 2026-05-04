package zelisline.ub.integrations.webhook.api.dto;

import java.time.Instant;
import java.util.List;

public record WebhookSubscriptionResponse(
        String id,
        String label,
        String targetUrl,
        List<String> events,
        boolean active,
        int failureCount,
        Instant createdAt,
        Instant updatedAt
) {
}
