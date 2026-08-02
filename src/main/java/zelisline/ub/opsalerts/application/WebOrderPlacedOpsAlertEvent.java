package zelisline.ub.opsalerts.application;

import java.math.BigDecimal;

/**
 * Snapshot published after storefront checkout — avoids Hibernate session issues in async listeners.
 */
public record WebOrderPlacedOpsAlertEvent(
        String businessId,
        String orderId,
        String customerName,
        String customerPhone,
        BigDecimal grandTotal,
        String currency
) {
}
