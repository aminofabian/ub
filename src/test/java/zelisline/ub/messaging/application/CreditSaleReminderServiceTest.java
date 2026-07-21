package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class CreditSaleReminderServiceTest {

    @Test
    void buildMessage_formatsItemizedLinesAmountBalanceAndLink() {
        List<CreditSaleReminderLineItem> items = List.of(
                new CreditSaleReminderLineItem("Sugar 2kg", new BigDecimal("2"), new BigDecimal("240.00")),
                new CreditSaleReminderLineItem("Milk 1L", BigDecimal.ONE, new BigDecimal("65.00")));
        String msg = CreditSaleReminderService.buildMessage(
                "Jane",
                "Mama's Kiosk",
                items,
                items.size(),
                new BigDecimal("305.00"),
                new BigDecimal("1240.00"),
                "KES",
                "https://palmart.co.ke/0714282874");
        assertEquals(
                "Hi Jane,\n\n"
                        + "You took on credit at Mama's Kiosk:\n"
                        + "• Sugar 2kg — KES 240\n"
                        + "• Milk 1L — KES 65\n\n"
                        + "This sale: KES 305\n"
                        + "Total tab: KES 1,240\n\n"
                        + "Pay here: https://palmart.co.ke/0714282874",
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
        String msg = CreditSaleReminderService.buildMessage(
                null,
                "Shop",
                items,
                items.size(),
                new BigDecimal("60.00"),
                new BigDecimal("100.00"),
                "KES",
                "https://palmart.co.ke/0711111111");
        assertEquals(
                "Hi,\n\n"
                        + "You took on credit at Shop:\n"
                        + "• A — KES 10\n"
                        + "• B — KES 10\n"
                        + "• C — KES 10\n"
                        + "• D — KES 10\n"
                        + "• E — KES 10\n"
                        + "• and 1 more\n\n"
                        + "This sale: KES 60\n"
                        + "Total tab: KES 100\n\n"
                        + "Pay here: https://palmart.co.ke/0711111111",
                msg);
    }

    @Test
    void buildMessage_fallsBackToCountWhenItemsEmpty() {
        String msg = CreditSaleReminderService.buildMessage(
                "Jane",
                "Mama's Kiosk",
                List.of(),
                1,
                new BigDecimal("10.50"),
                new BigDecimal("10.50"),
                "KES",
                "https://example.com/account");
        assertEquals(
                "Hi Jane,\n\n"
                        + "You took on credit at Mama's Kiosk:\n"
                        + "• 1 item\n\n"
                        + "This sale: KES 10.50\n"
                        + "Total tab: KES 10.50\n\n"
                        + "Pay here: https://example.com/account",
                msg);
    }
}
