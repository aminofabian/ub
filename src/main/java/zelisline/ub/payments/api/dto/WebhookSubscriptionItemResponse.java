package zelisline.ub.payments.api.dto;

public record WebhookSubscriptionItemResponse(
        String tillNumber,
        boolean success,
        String locationUrl,
        String errorMessage
) {
}
