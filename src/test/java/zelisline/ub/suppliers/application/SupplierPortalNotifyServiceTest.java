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
                "mpesa",
                "QK7X9M2P1A",
                List.of("PB-ABC123"),
                BigDecimal.ZERO,
                "https://palmart.co.ke/s/fresh-farm");
        assertEquals(
                "Palmart: paid 1500.00 KES (mpesa) for supply PB-ABC123. "
                        + "Ref: QK7X9M2P1A. Balance owed: 0.00 KES. "
                        + "View: https://palmart.co.ke/s/fresh-farm",
                msg);
    }

    @Test
    void buildPaidMessage_listsMultipleSupplies() {
        String msg = SupplierPortalNotifyService.buildPaidMessage(
                "Palmart",
                "KES",
                new BigDecimal("800.50"),
                "cash",
                "A1B2C3D4",
                List.of("PB-ONE", "PB-TWO"),
                new BigDecimal("200.00"),
                "https://palmart.co.ke/s/fresh-farm");
        assertEquals(
                "Palmart: paid 800.50 KES (cash) for supplies PB-ONE, PB-TWO. "
                        + "Ref: A1B2C3D4. Balance owed: 200.00 KES. "
                        + "View: https://palmart.co.ke/s/fresh-farm",
                msg);
        assertFalse(msg.contains("null"));
    }
}
