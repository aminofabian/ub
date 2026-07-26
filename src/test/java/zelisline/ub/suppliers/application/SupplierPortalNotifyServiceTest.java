package zelisline.ub.suppliers.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

class SupplierPortalNotifyServiceTest {

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
