package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Published after a POS sale commits with cash overpay credited to the customer wallet.
 */
public record WalletCreditNotificationEvent(
        String businessId,
        String saleId,
        String customerId,
        BigDecimal creditedAmount,
        int itemCount,
        List<CreditSaleReminderLineItem> items,
        BigDecimal walletBalance
) {
}
