package zelisline.ub.payments.api.dto;

import java.math.BigDecimal;

public record StkPushStatusResponse(
        String status,
        String checkoutRequestId,
        String merchantReference,
        String contextType,
        String contextId,
        String gatewayTransactionId,
        String failureReason,
        boolean success,
        boolean failed,
        boolean pending,
        BigDecimal amount
) {
}
