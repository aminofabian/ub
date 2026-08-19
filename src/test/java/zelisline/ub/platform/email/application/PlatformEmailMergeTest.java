package zelisline.ub.platform.email.application;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PlatformEmailMergeTest {

    @Test
    void replacesKnownTagsAndLeavesUnknown() {
        PlatformEmailMerge.Result result = PlatformEmailMerge.apply(
                "Finish {{businessName}}",
                "Hi {{name}}, open {{continueUrl}} {{unknownTag}}",
                new PlatformEmailMerge.Context(
                        "Jane",
                        "jane@shop.co.ke",
                        "Zetu Zetu",
                        "https://zetuzetu.kiosk.ke",
                        "https://zetuzetu.kiosk.ke/business"));

        assertThat(result.subject()).isEqualTo("Finish Zetu Zetu");
        assertThat(result.body()).contains("Hi Jane");
        assertThat(result.body()).contains("https://zetuzetu.kiosk.ke/business");
        assertThat(result.body()).contains("{{unknownTag}}");
        assertThat(result.unknownTags()).containsExactly("unknownTag");
    }

    @Test
    void markdownLiteRendersParagraphsBoldAndLinks() {
        String html = PlatformEmailMarkdown.toHtml("""
                Hello **there**.

                Open [Kiosk](https://kiosk.ke) to continue.
                """);
        assertThat(html).contains("<strong");
        assertThat(html).contains("href=\"https://kiosk.ke\"");
        assertThat(html).contains("</p>");
    }
}
