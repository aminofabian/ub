package zelisline.ub.marketplace.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class SupplierPortalEventNotifyServiceTest {

    @Test
    void buildOrdersActionUrl_includesPurchaseOrderId() {
        assertEquals(
                "/supplier-portal/orders?po=abc-123",
                SupplierPortalEventNotifyService.buildOrdersActionUrl("abc-123"));
    }

    @Test
    void buildOrdersActionUrl_fallsBackWithoutId() {
        assertEquals(
                "/supplier-portal/orders",
                SupplierPortalEventNotifyService.buildOrdersActionUrl(null));
        assertEquals(
                "/supplier-portal/orders",
                SupplierPortalEventNotifyService.buildOrdersActionUrl("  "));
    }

    @Test
    void buildPoReceivedBody_includesShopPoLinesAndTotal() {
        String body = SupplierPortalEventNotifyService.buildPoReceivedBody(
                "Palmart",
                "PO-1042",
                new SupplierPortalEventNotifyService.PoSummary(12, new BigDecimal("18400.00")));
        assertEquals("Palmart sent PO-1042 · 12 lines · Ksh 18400.", body);
    }

    @Test
    void buildPoReceivedBody_omitsTotalWhenMissing() {
        String body = SupplierPortalEventNotifyService.buildPoReceivedBody(
                "Palmart",
                "PO-1",
                SupplierPortalEventNotifyService.PoSummary.empty());
        assertEquals("Palmart sent PO-1.", body);
        assertTrue(!body.contains("Ksh"));
    }

    @Test
    void formatMoneyPlain_stripsTrailingZeros() {
        assertEquals("100", SupplierPortalEventNotifyService.formatMoneyPlain(new BigDecimal("100.00")));
        assertEquals("99.5", SupplierPortalEventNotifyService.formatMoneyPlain(new BigDecimal("99.50")));
    }
}
