package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record MarketplaceEscrowHoldResponse(
        String id,
        String businessId,
        String supplierId,
        String marketplaceSupplierId,
        String purchaseOrderId,
        String supplierInvoiceId,
        BigDecimal amount,
        String currency,
        String status,
        String releaseTrigger,
        String fundedLedgerReference,
        String kopokopoSendMoneyId,
        String failureReason,
        String note,
        Instant createdAt,
        Instant updatedAt,
        Instant releasedAt,
        Instant settledAt
) {
}
