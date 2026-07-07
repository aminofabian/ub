package zelisline.ub.messaging.dto;

import java.util.List;

public record MetaWebhookStatus(
    String id,
    String status,       // sent, delivered, read, failed
    String timestamp,
    String recipientId,
    Conversation conversation,
    Pricing pricing,
    List<MetaWebhookError> errors,
    String bizOpaqueCallbackData
) {
    public record Conversation(String id, Origin origin) {
        public record Origin(String type) {} // user_initiated, business_initiated, referral_conversion
    }
    public record Pricing(String category, Double pricingModel) {}
}
