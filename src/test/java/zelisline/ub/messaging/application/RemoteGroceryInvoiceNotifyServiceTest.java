package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class RemoteGroceryInvoiceNotifyServiceTest {

    @Test
    void buildMessage_listsItemsAndPayLink() {
        String msg = RemoteGroceryInvoiceNotifyService.buildMessage(
                "Palmart Fresh Foods & Butchery",
                "GI-KQU8NIUQUN",
                new BigDecimal("5.00"),
                1,
                List.of(new CreditSaleReminderLineItem(
                        "Kales (Sukuma)", BigDecimal.ONE, new BigDecimal("5.00"))),
                "KES",
                "https://palmart.co.ke/0714282874");
        assertEquals(
                "Hi,\n\n"
                        + "Your bill at Palmart Fresh Foods & Butchery is ready:\n"
                        + "• Kales (Sukuma) — KES 5\n"
                        + "\nAmount: KES 5"
                        + "\nRef: GI-KQU8NIUQUN"
                        + "\n\nCheck your phone for the M-Pesa prompt to pay."
                        + "\nView / pay: https://palmart.co.ke/0714282874",
                msg);
    }
}
