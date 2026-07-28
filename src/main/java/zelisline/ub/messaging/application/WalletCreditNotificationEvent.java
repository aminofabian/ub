package zelisline.ub.messaging.application;

import java.math.BigDecimal;
import java.util.List;

/**
 * Published after a POS sale commits with cash overpay applied to tab and/or wallet.
 */
public record WalletCreditNotificationEvent(
        String businessId,
        String saleId,
        String customerId,
        BigDecimal walletCreditedAmount,
        BigDecimal tabAppliedAmount,
        int itemCount,
        List<CreditSaleReminderLineItem> items,
        BigDecimal walletBalance,
        BigDecimal balanceOwed
) {
}
