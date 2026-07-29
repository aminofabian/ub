package zelisline.ub.payments.api.dto;

import java.util.List;

import jakarta.validation.constraints.Size;

/**
 * Request body for creating KopoKopo buygoods webhook subscriptions for one or more tills.
 * When {@code tillNumbers} is empty/null, the gateway config's till + webhookTillNumbers are used.
 */
public record SubscribeWebhookTillsRequest(
        @Size(max = 20) List<@Size(max = 40) String> tillNumbers
) {
}
