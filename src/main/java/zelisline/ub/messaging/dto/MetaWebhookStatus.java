package zelisline.ub.messaging.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MetaWebhookStatus(
    String id,
    String status,       // sent, delivered, read, failed
    String timestamp,
    @JsonProperty("recipient_id") String recipientId,
    Conversation conversation,
    Pricing pricing,
    List<MetaWebhookError> errors,
    @JsonProperty("biz_opaque_callback_data") String bizOpaqueCallbackData
) {
    public record Conversation(String id, Origin origin) {
        public record Origin(String type) {} // user_initiated, business_initiated, referral_conversion
    }
    public record Pricing(String category, Double pricingModel) {}
}
