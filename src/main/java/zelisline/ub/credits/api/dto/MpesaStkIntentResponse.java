package zelisline.ub.credits.api.dto;

import java.math.BigDecimal;

public record MpesaStkIntentResponse(String intentId, String checkoutRequestId, String status, BigDecimal amount) {
}
