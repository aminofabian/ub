package zelisline.ub.storefront.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.tenancy.api.dto.TenantBrandingDto;

class OrderConfirmationEmailRendererTest {

    private final OrderConfirmationEmailRenderer renderer = new OrderConfirmationEmailRenderer();

    @Test
    void usesBrandingDisplayNameAndColours() {
        TenantBrandingDto branding = new TenantBrandingDto(
                "Palmart",
                null,
                null,
                "#0B6E4F",
                "#08A045",
                null,
                null,
                null,
                null,
                null);

        String html = renderer.renderHtml(
                sampleOrder(), List.of(sampleLine()), "Mirema", branding, "Kiosk", "palmart");

        assertTrue(html.contains("Palmart"));
        assertTrue(!html.contains(">Kiosk<") && !html.contains("Kiosk &nbsp;"));
        assertTrue(html.contains("#0B6E4F"));
        assertTrue(html.contains("#08A045"));
    }

    @Test
    void skipsPlatformPlaceholderNameAndUsesSlug() {
        assertEquals(
                "Palmart",
                OrderConfirmationEmailRenderer.resolveStoreName(null, "Kiosk", "palmart"));
    }

    @Test
    void fallsBackToBusinessNameWhenDisplayNameMissing() {
        assertEquals(
                "Palmart",
                OrderConfirmationEmailRenderer.resolveStoreName(
                        TenantBrandingDto.defaults(null), "Palmart", "palmart"));
        assertEquals(
                "Palmart",
                OrderConfirmationEmailRenderer.resolveStoreName(null, "Palmart", null));
    }

    @Test
    void sanitizeHexAcceptsShortAndLongForms() {
        assertEquals("#0B6E4F", OrderConfirmationEmailRenderer.sanitizeHex("#0b6e4f", "#000000"));
        assertEquals("#AABBCC", OrderConfirmationEmailRenderer.sanitizeHex("#abc", "#000000"));
        assertEquals("#2D6A4F", OrderConfirmationEmailRenderer.sanitizeHex("not-a-color", "#2D6A4F"));
    }

    private static WebOrder sampleOrder() {
        WebOrder order = new WebOrder();
        order.setId("order-1");
        order.setCreatedAt(Instant.parse("2026-07-20T14:51:00Z"));
        order.setGrandTotal(new BigDecimal("15.00"));
        order.setCurrency("KES");
        order.setStatus("pending_payment");
        return order;
    }

    private static WebOrderLine sampleLine() {
        WebOrderLine line = new WebOrderLine();
        line.setItemName("Kichungi Small Size");
        line.setQuantity(BigDecimal.ONE);
        line.setUnitPrice(new BigDecimal("15.00"));
        line.setLineTotal(new BigDecimal("15.00"));
        return line;
    }
}
