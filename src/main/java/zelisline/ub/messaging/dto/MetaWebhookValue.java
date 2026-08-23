package zelisline.ub.messaging.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

public record MetaWebhookValue(
    @JsonProperty("messaging_product") String messagingProduct,
    MetaWebhookMetadata metadata,
    List<MetaWebhookContact> contacts,
    @JsonProperty("messages") List<MetaWebhookMessage> messages,
    @JsonProperty("statuses") List<MetaWebhookStatus> statuses,
    List<MetaWebhookError> errors
) {}
