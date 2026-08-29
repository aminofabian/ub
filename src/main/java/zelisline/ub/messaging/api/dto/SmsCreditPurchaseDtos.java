package zelisline.ub.messaging.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

import zelisline.ub.messaging.domain.SmsCreditPurchaseStatus;

/** Top-up checkout records shared by the tenant buy flow and SA drill-down. */
public record SmsCreditPurchaseDtos() {

    public record SmsCreditPurchaseRequest(
            Integer credits,
            String phone
    ) {
    }

    public record SmsCreditPurchaseResponse(
            String id,
            int credits,
            BigDecimal amountKes,
            SmsCreditPurchaseStatus status,
            String phoneNumber,
            String message
    ) {
    }

    public record SmsCreditPurchaseStatusResponse(
            String id,
            SmsCreditPurchaseStatus status,
            BigDecimal amountKes,
            String mpesaReceipt,
            Instant paidAt,
            boolean needsRetry
    ) {
    }
}
