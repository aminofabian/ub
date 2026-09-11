package zelisline.ub.ai.application.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OpenRouterImageClientTest {

    @Test
    void payloadAsksForSquareTransparentPng() {
        var payload = OpenRouterImageClient.buildPayload("google/gemini-2.5-flash-image", "a leaf");
        assertThat(payload.get("model")).isEqualTo("google/gemini-2.5-flash-image");
        assertThat(payload.get("aspect_ratio")).isEqualTo("1:1");
        assertThat(payload.get("output_format")).isEqualTo("png");
        assertThat(payload.get("background")).isEqualTo("transparent");
        assertThat(payload.get("n")).isEqualTo(1);
    }

    @Test
    void payloadCanAttachAReferenceLogoAndStayOpaque() {
        var payload = OpenRouterImageClient.buildPayload(
                "google/gemini-2.5-flash-image",
                "a leaf",
                "data:image/png;base64,abcd",
                false);
        assertThat(payload.get("image")).isEqualTo(java.util.List.of("data:image/png;base64,abcd"));
        assertThat(payload).doesNotContainKey("background");
    }

    @Test
    void payloadCanAttachAReferenceLogoAndStayTransparent() {
        var payload = OpenRouterImageClient.buildPayload(
                "google/gemini-2.5-flash-image",
                "a leaf",
                "data:image/png;base64,abcd",
                true);
        assertThat(payload.get("image")).isEqualTo(java.util.List.of("data:image/png;base64,abcd"));
        assertThat(payload.get("background")).isEqualTo("transparent");
    }

    @Test
    void imagesUrlAppendsImagesAndStripsChatCompletions() {
        assertThat(OpenRouterImageClient.imagesUrl(null))
                .isEqualTo("https://openrouter.ai/api/v1/images");
        assertThat(OpenRouterImageClient.imagesUrl("https://openrouter.ai/api/v1"))
                .isEqualTo("https://openrouter.ai/api/v1/images");
        assertThat(OpenRouterImageClient.imagesUrl("https://openrouter.ai/api/v1/chat/completions"))
                .isEqualTo("https://openrouter.ai/api/v1/images");
        assertThat(OpenRouterImageClient.imagesUrl("openrouter.ai/api/v1/"))
                .isEqualTo("https://openrouter.ai/api/v1/images");
    }

    @Test
    void parseReadsB64AndUsage() {
        String body = """
                {"data":[{"b64_json":"abcd","media_type":"image/png"}],
                 "usage":{"prompt_tokens":4,"completion_tokens":80}}
                """;
        var image = OpenRouterImageClient.parse(body, "google/gemini-2.5-flash-image");
        assertThat(image.base64()).isEqualTo("abcd");
        assertThat(image.mimeType()).isEqualTo("image/png");
        assertThat(image.promptTokens()).isEqualTo(4);
        assertThat(image.completionTokens()).isEqualTo(80);
    }

    @Test
    void parseStripsDataUrlPrefix() {
        String body = """
                {"data":[{"b64_json":"data:image/png;base64,abcd"}]}
                """;
        var image = OpenRouterImageClient.parse(body, "google/gemini-2.5-flash-image");
        assertThat(image.base64()).isEqualTo("abcd");
    }

    @Test
    void parseKeepsUrlWhenB64Missing() {
        String body = """
                {"data":[{"url":"https://example.com/logo.png"}]}
                """;
        var image = OpenRouterImageClient.parse(body, "google/gemini-2.5-flash-image");
        assertThat(image.base64()).isNull();
        assertThat(image.url()).isEqualTo("https://example.com/logo.png");
    }

    @Test
    void parseRejectsEmptyData() {
        assertThatThrownBy(() -> OpenRouterImageClient.parse("{\"data\":[]}", "google/gemini-2.5-flash-image"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no image");
    }

    @Test
    void creditsErrorIsClear() {
        String msg = OpenRouterImageClient.userFacingImageError(
                402, "{\"error\":{\"message\":\"Insufficient credits. Add more using https://openrouter.ai/credits\"}}");
        assertThat(msg).contains("credits");
    }
}
