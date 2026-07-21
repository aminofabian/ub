package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CreditTabPaymentConfirmationServiceTest {

    @Test
    void buildMessage_showsPaidAmountAndRemainingBalanceWithPayLink() {
        String msg = CreditTabPaymentConfirmationService.buildMessage(
                "Amina",
                "Palmart",
                new BigDecimal("150.00"),
                new BigDecimal("350.00"),
                "KES",
                "https://palmart.co.ke/0714282874");
        assertEquals(
                "Hi Amina,\n\n"
                        + "We received your M-Pesa payment of KES 150 at Palmart.\n"
                        + "Remaining tab balance: KES 350\n\n"
                        + "Pay here: https://palmart.co.ke/0714282874\n\n"
                        + "Thank you!",
                msg);
    }

    @Test
    void buildMessage_whenFullyPaid_omitsPayLink() {
        String msg = CreditTabPaymentConfirmationService.buildMessage(
                "Amina",
                "Palmart",
                new BigDecimal("500.00"),
                BigDecimal.ZERO,
                "KES",
                null);
        assertEquals(
                "Hi Amina,\n\n"
                        + "We received your M-Pesa payment of KES 500 at Palmart.\n"
                        + "Your tab is now fully paid.\n\n"
                        + "Thank you!",
                msg);
    }
}
