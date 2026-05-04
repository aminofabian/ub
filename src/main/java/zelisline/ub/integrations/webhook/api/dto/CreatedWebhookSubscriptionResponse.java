package zelisline.ub.integrations.webhook.api.dto;

import java.time.Instant;
import java.util.List;

public record CreatedWebhookSubscriptionResponse(
        String id,
        String signingSecret,
        String label,
        String targetUrl,
        List<String> events,
        Instant createdAt
) {
}
