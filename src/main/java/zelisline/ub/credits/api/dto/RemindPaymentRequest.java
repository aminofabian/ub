package zelisline.ub.credits.api.dto;

import jakarta.validation.constraints.Size;

public record RemindPaymentRequest(
        /** auto (default), whatsapp, or sms */
        @Size(max = 16) String channel
) {
}
