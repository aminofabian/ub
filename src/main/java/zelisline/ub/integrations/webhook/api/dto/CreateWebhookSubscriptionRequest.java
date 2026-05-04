package zelisline.ub.integrations.webhook.api.dto;

import java.util.List;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

public record CreateWebhookSubscriptionRequest(
        @NotBlank String label,
        @NotBlank String targetUrl,
        @NotEmpty List<String> events
) {
}
