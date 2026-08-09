package zelisline.ub.payments.domain.spi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.Test;

class SendMoneyRequestTest {

    @Test
    void toMobileWalletSetsDestinationType() {
        SendMoneyRequest req = SendMoneyRequest.toMobileWallet(
                Map.of("clientId", "x"),
                "https://example.com",
                "254710514157",
                BigDecimal.TEN,
                "KES",
                "test",
                "1234",
                Map.of("k", "v"));
        assertEquals(SendMoneyRequest.DEST_MOBILE_WALLET, req.destinationType());
        assertEquals("254710514157", req.phoneNumber());
        assertNull(req.tillNumber());
        assertNull(req.paybillNumber());
    }

    @Test
    void tillAndPaybillFieldsRoundTrip() {
        SendMoneyRequest till = new SendMoneyRequest(
                Map.of(),
                "https://cb",
                SendMoneyRequest.DEST_TILL,
                null,
                "567890",
                null,
                null,
                BigDecimal.valueOf(100),
                "KES",
                "Supply INV-1",
                "4321",
                Map.of());
        assertEquals(SendMoneyRequest.DEST_TILL, till.destinationType());
        assertEquals("567890", till.tillNumber());

        SendMoneyRequest paybill = new SendMoneyRequest(
                Map.of(),
                "https://cb",
                SendMoneyRequest.DEST_PAYBILL,
                null,
                null,
                "247247",
                "ACC-99",
                BigDecimal.valueOf(50),
                "KES",
                "Supply INV-2",
                null,
                null);
        assertEquals(SendMoneyRequest.DEST_PAYBILL, paybill.destinationType());
        assertEquals("247247", paybill.paybillNumber());
        assertEquals("ACC-99", paybill.paybillAccountNumber());
    }
}
