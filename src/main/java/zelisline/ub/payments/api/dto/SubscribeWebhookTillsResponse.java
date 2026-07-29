package zelisline.ub.payments.api.dto;

import java.util.List;

public record SubscribeWebhookTillsResponse(
        String webhookUrl,
        String eventType,
        List<WebhookSubscriptionItemResponse> subscriptions
) {
}
