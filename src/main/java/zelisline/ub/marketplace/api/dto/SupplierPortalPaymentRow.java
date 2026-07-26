package zelisline.ub.marketplace.api.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record SupplierPortalPaymentRow(
        String paymentId,
        String businessId,
        String businessName,
        String localSupplierId,
        Instant paidAt,
        String paymentMethod,
        BigDecimal amount,
        String reference,
        String status,
        BigDecimal shopOpenBalance
) {
}
