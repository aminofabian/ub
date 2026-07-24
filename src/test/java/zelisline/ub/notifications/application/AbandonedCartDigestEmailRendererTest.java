package zelisline.ub.notifications.application;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import zelisline.ub.tenancy.api.dto.TenantBrandingDto;

class AbandonedCartDigestEmailRendererTest {

    private final AbandonedCartDigestEmailRenderer renderer = new AbandonedCartDigestEmailRenderer();

    @Test
    void rendersBrandedHtmlWithItemImagesAndShopName() {
        TenantBrandingDto branding = new TenantBrandingDto(
                "Palmart",
                "https://cdn.example.com/palmart-logo.png",
                null,
                "#0B6E4F",
                "#08A045",
                null,
                null,
                null,
                null,
                null);

        List<AbandonedCartDigestEmailRenderer.ItemPreview> items = List.of(
                new AbandonedCartDigestEmailRenderer.ItemPreview(
                        "item-1",
                        "Fresh Milk 500ml",
                        null,
                        "https://cdn.example.com/milk.jpg",
                        new BigDecimal("12"),
                        8L),
                new AbandonedCartDigestEmailRenderer.ItemPreview(
                        "item-2",
                        "Brown Bread",
                        "Wholemeal",
                        null,
                        new BigDecimal("5"),
                        3L));

        String html = renderer.renderHtml(
                branding,
                "Kiosk",
                "palmart",
                39,
                items,
                "https://app.kiosk.ke/storefront/web-orders");

        assertTrue(html.contains("Palmart"));
        assertTrue(html.contains("https://cdn.example.com/palmart-logo.png"));
        assertTrue(html.contains("#0B6E4F"));
        assertTrue(html.contains("Abandoned carts"));
        assertTrue(html.contains("39 carts still have items waiting"));
        assertTrue(html.contains("Fresh Milk 500ml"));
        assertTrue(html.contains("https://cdn.example.com/milk.jpg"));
        assertTrue(html.contains("Brown Bread"));
        assertTrue(html.contains("Wholemeal"));
        assertTrue(html.contains("Review abandoned carts"));
        assertTrue(html.contains("https://app.kiosk.ke/storefront/web-orders"));
        assertTrue(html.contains("DM Sans"));
        assertTrue(html.contains("Cormorant Garamond"));
        assertFalse(html.contains("Open in Palmart"));
        assertFalse(html.contains("linear-gradient"));
    }

    @Test
    void subjectUsesTenantWordmark() {
        TenantBrandingDto branding = new TenantBrandingDto(
                "Palmart | Groceries in [Area]",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);

        String subject = renderer.renderSubject(branding, "Kiosk", "palmart");
        assertTrue(subject.startsWith("Abandoned carts — Palmart"));
    }

    @Test
    void plainTextListsAbandonedItems() {
        String text = renderer.renderPlainText(
                TenantBrandingDto.defaults("Palmart"),
                "Palmart",
                "palmart",
                2,
                List.of(new AbandonedCartDigestEmailRenderer.ItemPreview(
                        "1", "Avocado", null, null, BigDecimal.ONE, 2L)),
                "https://example.com/carts");

        assertTrue(text.contains("Palmart"));
        assertTrue(text.contains("2 carts still have items waiting"));
        assertTrue(text.contains("Avocado"));
        assertTrue(text.contains("https://example.com/carts"));
    }

    @Test
    void rendersWithoutItemsStillBranded() {
        String html = renderer.renderHtml(
                TenantBrandingDto.defaults("Uzapoint"),
                "Uzapoint",
                "uzapoint",
                1,
                List.of(),
                "/storefront/web-orders");

        assertTrue(html.contains("Uzapoint"));
        assertTrue(html.contains("1 cart still has items waiting"));
        assertTrue(html.contains("Review abandoned carts"));
        assertFalse(html.contains("Left behind"));
    }
}
