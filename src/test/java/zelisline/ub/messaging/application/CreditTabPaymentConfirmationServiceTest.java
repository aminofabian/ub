package zelisline.ub.messaging.application;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class CreditTabPaymentConfirmationServiceTest {

    private static final String PAY_URL = "https://palmart.co.ke/0714282874";

    @Test
    void buildMessage_namesTheRailWhenKnown() {
        String msg = CreditTabPaymentConfirmationService.buildMessage(
                "Jane",
                "Mama's Kiosk",
                new BigDecimal("500.00"),
                new BigDecimal("1240.00"),
                "KES",
                PAY_URL,
                CreditTabPaymentConfirmationEvent.METHOD_MPESA);
        assertEquals(
                "Hi Jane,\n\n"
                        + "We received your M-Pesa payment of KES 500 at Mama's Kiosk.\n"
                        + "Remaining tab balance: KES 1,240\n\n"
                        + "Pay here: " + PAY_URL
                        + "\n\nThank you!",
                msg);
    }

    @Test
    void buildMessage_usesCashLabelForAdminRecordedPayments() {
        String msg = CreditTabPaymentConfirmationService.buildMessage(
                "Jane",
                "Mama's Kiosk",
                new BigDecimal("500.00"),
                new BigDecimal("0.00"),
                "KES",
                PAY_URL,
                CreditTabPaymentConfirmationEvent.METHOD_CASH);
        assertEquals(
                "Hi Jane,\n\n"
                        + "We received your cash payment of KES 500 at Mama's Kiosk.\n"
                        + "Your tab is now fully paid.\n\n"
                        + "Thank you!",
                msg);
    }

    @Test
    void buildMessage_staysNeutralWhenTheRailIsUnknown() {
        // Six-arg overload: no payment method, so the wording must read naturally.
        String msg = CreditTabPaymentConfirmationService.buildMessage(
                null,
                "Shop",
                new BigDecimal("10.50"),
                new BigDecimal("10.50"),
                "KES",
                null);
        assertEquals(
                "Hi,\n\n"
                        + "We received your payment of KES 10.50 at Shop.\n"
                        + "Remaining tab balance: KES 10.50\n\n"
                        + "Thank you!",
                msg);
    }
}
