package zelisline.ub.identity.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WelcomeEmailRendererTest {

    private final WelcomeEmailRenderer renderer = new WelcomeEmailRenderer();

    @Test
    void subjectIsFixedWelcomeCopy() {
        assertThat(renderer.renderSubject()).isEqualTo("Welcome to Kiosk! \uD83C\uDF89");
    }

    @Test
    void plainTextSubstitutesNameAndBusiness() {
        String body = renderer.renderPlainText("Jane Owner", "Uzapoint Mart");

        assertThat(body).contains("Hi Jane Owner,");
        assertThat(body).contains("excited to have Uzapoint Mart on board");
        assertThat(body).contains("Setting up your online store");
        assertThat(body).contains("M-Pesa integration");
        assertThat(body).contains("completely free of charge");
        assertThat(body).contains("0714 282 874");
        assertThat(body).contains("admin@kiosk.ke");
        assertThat(body).contains("Kiosk Team");
    }

    @Test
    void plainTextFallsBackWhenNameOrBusinessMissing() {
        String body = renderer.renderPlainText(null, "  ");

        assertThat(body).contains("Hi there,");
        assertThat(body).contains("excited to have your business on board");
    }

    @Test
    void htmlMatchesKioskVisualSystem() {
        String html = renderer.renderHtml("Jane Owner", "Uzapoint Mart");

        assertThat(html).contains("<!DOCTYPE html>");
        assertThat(html).contains("Welcome to Kiosk");
        assertThat(html).contains("Hi Jane Owner,");
        assertThat(html).contains("Uzapoint Mart");
        assertThat(html).contains("completely free of charge");
        assertThat(html).contains("0714 282 874");
        assertThat(html).contains("admin@kiosk.ke");
        assertThat(html).contains("mailto:admin@kiosk.ke");
        assertThat(html).contains(WelcomeEmailRenderer.PAGE_BG);
        assertThat(html).contains(WelcomeEmailRenderer.GREEN);
        assertThat(html).contains("Cormorant+Garamond");
        assertThat(html).contains("DM+Sans");
        assertThat(html).contains("Kiosk");
        assertThat(html).contains("You&rsquo;re in");
        assertThat(html).doesNotContain("<svg");
    }

    @Test
    void htmlEscapesUserProvidedNames() {
        String html = renderer.renderHtml("<script>", "A & B \"Shop\"");

        assertThat(html).contains("&lt;script&gt;");
        assertThat(html).contains("A &amp; B &quot;Shop&quot;");
        assertThat(html).doesNotContain("<script>");
    }
}
