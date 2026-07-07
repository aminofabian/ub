package zelisline.ub.messaging.dto;

public record MetaWebhookChange(
    String field,
    MetaWebhookValue value
) {}
