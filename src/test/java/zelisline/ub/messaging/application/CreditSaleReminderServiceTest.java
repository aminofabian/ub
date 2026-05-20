package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CreditSaleReminderServiceTest {

    @Test
    void buildMessage_formatsItemsAmountAndLink() {
        String msg = CreditSaleReminderService.buildMessage(
                5,
                new BigDecimal("2450"),
                "KES",
                "https://palmart.co.ke/shop/account");
        assertEquals(
                "You took 5 items on credit worth KES 2,450.\n"
                        + "Pay here: https://palmart.co.ke/shop/account",
                msg);
    }

    @Test
    void buildMessage_singularItem() {
        String msg = CreditSaleReminderService.buildMessage(
                1, new BigDecimal("10.50"), "KES", "https://example.com/account");
        assertEquals(
                "You took 1 item on credit worth KES 10.50.\nPay here: https://example.com/account",
                msg);
    }
}
