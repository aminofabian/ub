package zelisline.ub.platform.email.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformEmailMarkdownTest {

    @Test
    void imageOnlyBlockRendersImg() {
        String html = PlatformEmailMarkdown.toHtml(
                "Hi\n\n![Step 1](https://kiosk.ke/email/mpesa-step-1-hub.svg)\n\nDone");
        assertThat(html).contains("<img src=\"https://kiosk.ke/email/mpesa-step-1-hub.svg\"");
        assertThat(html).contains("alt=\"Step 1\"");
        assertThat(html).contains("Hi");
        assertThat(html).contains("Done");
    }

    @Test
    void plainTextStripsImages() {
        String text = PlatformEmailMarkdown.toPlainText(
                "Hello\n\n![Guide](https://kiosk.ke/email/x.svg)\n\nWorld");
        assertThat(text).doesNotContain("http");
        assertThat(text).contains("Hello");
        assertThat(text).contains("World");
    }
}
