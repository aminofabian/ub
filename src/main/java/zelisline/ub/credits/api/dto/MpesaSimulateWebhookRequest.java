package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.NotBlank;

public record MpesaSimulateWebhookRequest(@NotBlank String intentId, @NotBlank String businessId) {
}
