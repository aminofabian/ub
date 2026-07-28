package zelisline.ub.suppliers.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class SupplierPortalNotifyServiceTest {

    @Test
    void buildPostedMessage_shortSupplyReceivedTemplate() {
        String msg = SupplierPortalNotifyService.buildPostedMessage(
                "Palmart",
                "KES",
                "PB-ED29096EE823",
                new BigDecimal("8999.00"),
                "https://palmart.co.ke/s/david-mutuku");
        assertEquals(
                "Palmart: Thanks! We've received supply PB-ED29096EE823. "
                        + "KES 8,999.00 will be paid within 48hrs. "
                        + "https://palmart.co.ke/s/david-mutuku",
                msg);
    }

    @Test
    void buildPostedMessage_omitsUrlWhenMissing() {
        String msg = SupplierPortalNotifyService.buildPostedMessage(
                "Shop",
                "KES",
                "PB-1",
                new BigDecimal("100"),
                null);
        assertEquals(
                "Shop: Thanks! We've received supply PB-1. KES 100.00 will be paid within 48hrs.",
                msg);
    }

    @Test
    void buildPaidMessage_includesReferenceAmountAndSupply() {
        String msg = SupplierPortalNotifyService.buildPaidMessage(
                "Palmart",
                "KES",
                new BigDecimal("1500.00"),
                "QK7X9M2P1A",
                List.of("PB-ABC123"),
                BigDecimal.ZERO,
                "https://palmart.co.ke/s/fresh-farm");
        assertEquals(
                "Palmart: Payment of KES 1,500.00 received for supply PB-ABC123. "
                        + "Ref: QK7X9M2P1A. Balance: KES 0.00. "
                        + "https://palmart.co.ke/s/fresh-farm",
                msg);
    }

    @Test
    void buildPaidMessage_listsMultipleSupplies() {
        String msg = SupplierPortalNotifyService.buildPaidMessage(
                "Palmart",
                "KES",
                new BigDecimal("800.50"),
                "A1B2C3D4",
                List.of("PB-ONE", "PB-TWO"),
                new BigDecimal("200.00"),
                "https://palmart.co.ke/s/fresh-farm");
        assertEquals(
                "Palmart: Payment of KES 800.50 received for supplies PB-ONE, PB-TWO. "
                        + "Ref: A1B2C3D4. Balance: KES 200.00. "
                        + "https://palmart.co.ke/s/fresh-farm",
                msg);
        assertFalse(msg.contains("null"));
    }

    @Test
    void buildPaidMessage_omitsMethodAndViewPrefix() {
        String msg = SupplierPortalNotifyService.buildPaidMessage(
                "Palmart",
                "KES",
                new BigDecimal("440.00"),
                "UGS970UE5Y",
                List.of("PB-6EBCC58AA66F"),
                BigDecimal.ZERO,
                "https://palmart.co.ke/s/simon-mukiha-broadways");
        assertEquals(
                "Palmart: Payment of KES 440.00 received for supply PB-6EBCC58AA66F. "
                        + "Ref: UGS970UE5Y. Balance: KES 0.00. "
                        + "https://palmart.co.ke/s/simon-mukiha-broadways",
                msg);
        assertFalse(msg.contains("(cash)"));
        assertFalse(msg.contains("View:"));
        assertFalse(msg.contains("Balance owed"));
    }
}
