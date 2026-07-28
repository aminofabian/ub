package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class WalletCreditNotificationServiceTest {

    @Test
    void buildMessage_formatsItemsCreditBalanceAndLink() {
        List<CreditSaleReminderLineItem> items = List.of(
                new CreditSaleReminderLineItem("Sugar 2kg", new BigDecimal("2"), new BigDecimal("240.00")),
                new CreditSaleReminderLineItem("Milk 1L", BigDecimal.ONE, new BigDecimal("65.00")));
        String msg = WalletCreditNotificationService.buildMessage(
                "Jane",
                "Mama's Kiosk",
                items,
                items.size(),
                new BigDecimal("130.00"),
                BigDecimal.ZERO,
                new BigDecimal("250.00"),
                BigDecimal.ZERO,
                "KES",
                "https://palmart.co.ke/0714282874");
        assertEquals(
                "Hi Jane,\n\n"
                        + "KES 130 was added to your wallet at Mama's Kiosk (change from your purchase):\n"
                        + "• Sugar 2kg — KES 240\n"
                        + "• Milk 1L — KES 65\n\n"
                        + "Wallet balance: KES 250\n\n"
                        + "View purchases: https://palmart.co.ke/0714282874",
                msg);
    }

    @Test
    void buildMessage_mentionsTabPaydownWhenCreditConsidered() {
        List<CreditSaleReminderLineItem> items = List.of(
                new CreditSaleReminderLineItem("Bread", BigDecimal.ONE, new BigDecimal("60.00")));
        String msg = WalletCreditNotificationService.buildMessage(
                "Jane",
                "Mama's Kiosk",
                items,
                1,
                new BigDecimal("30.00"),
                new BigDecimal("100.00"),
                new BigDecimal("30.00"),
                new BigDecimal("50.00"),
                "KES",
                "https://palmart.co.ke/0714282874");
        assertEquals(
                "Hi Jane,\n\n"
                        + "KES 100 went to your tab and KES 30 to your wallet at Mama's Kiosk (change from your purchase):\n"
                        + "• Bread — KES 60\n\n"
                        + "Tab balance: KES 50\n"
                        + "Wallet balance: KES 30\n\n"
                        + "View purchases: https://palmart.co.ke/0714282874",
                msg);
    }

    @Test
    void buildMessage_capsItemLinesAndShowsMore() {
        List<CreditSaleReminderLineItem> items = List.of(
                new CreditSaleReminderLineItem("A", BigDecimal.ONE, BigDecimal.TEN),
                new CreditSaleReminderLineItem("B", BigDecimal.ONE, BigDecimal.TEN),
                new CreditSaleReminderLineItem("C", BigDecimal.ONE, BigDecimal.TEN),
                new CreditSaleReminderLineItem("D", BigDecimal.ONE, BigDecimal.TEN),
                new CreditSaleReminderLineItem("E", BigDecimal.ONE, BigDecimal.TEN),
                new CreditSaleReminderLineItem("F", BigDecimal.ONE, BigDecimal.TEN));
        String msg = WalletCreditNotificationService.buildMessage(
                null,
                "Shop",
                items,
                items.size(),
                new BigDecimal("40.00"),
                BigDecimal.ZERO,
                new BigDecimal("40.00"),
                BigDecimal.ZERO,
                "KES",
                "https://palmart.co.ke/0711111111");
        assertEquals(
                "Hi,\n\n"
                        + "KES 40 was added to your wallet at Shop (change from your purchase):\n"
                        + "• A — KES 10\n"
                        + "• B — KES 10\n"
                        + "• C — KES 10\n"
                        + "• D — KES 10\n"
                        + "• E — KES 10\n"
                        + "• and 1 more\n\n"
                        + "Wallet balance: KES 40\n\n"
                        + "View purchases: https://palmart.co.ke/0711111111",
                msg);
    }
}
