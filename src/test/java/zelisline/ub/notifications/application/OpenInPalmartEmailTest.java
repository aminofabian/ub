package zelisline.ub.notifications.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenInPalmartEmailTest {

    @Test
    void fridayPocketLinkUsesTheAppOrigin() {
        String html = OpenInPalmartEmail.html(
                "https://kiosk.ke",
                "Friday Pocket",
                "Pocket KES 3948.36 before the weekend.",
                "/business?pocket=1");

        assertTrue(html.contains("href=\"https://kiosk.ke/business?pocket=1\""));
        assertFalse(html.contains("href=\"/business?pocket=1\""));
        assertFalse(html.contains("http://business/"));
    }

    @Test
    void plainTextCarriesTheSameAbsoluteLink() {
        String text = OpenInPalmartEmail.text(
                "https://kiosk.ke/",
                "Friday Pocket",
                "Pocket KES 100.",
                "/business?pocket=1");

        assertEquals(
                "Friday Pocket: Pocket KES 100.\n\nOpen in Palmart: https://kiosk.ke/business?pocket=1",
                text);
    }

    @Test
    void leavesAbsoluteLinksUntouched() {
        assertEquals(
                "https://kiosk.ke/business?pocket=1",
                OpenInPalmartEmail.absoluteUrl(
                        "http://localhost:3000",
                        "https://kiosk.ke/business?pocket=1",
                        "/"));
    }
}
