package zelisline.ub.messaging.application;

import java.math.BigDecimal;

/**
 * Line item included in a credit sale reminder message.
 */
public record CreditSaleReminderLineItem(
        String name,
        BigDecimal quantity,
        BigDecimal lineTotal
) {
}
