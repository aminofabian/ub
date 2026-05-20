package zelisline.ub.messaging.application;

import java.math.BigDecimal;

/**
 * Published after a POS sale commits with customer tab (credit) tender.
 */
public record CreditSaleReminderEvent(
        String businessId,
        String saleId,
        String customerId,
        BigDecimal creditAmount,
        int itemCount
) {
}
