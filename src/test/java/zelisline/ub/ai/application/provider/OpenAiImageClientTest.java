package zelisline.ub.ai.application.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class OpenAiImageClientTest {

    @Test
    void gptImagePayloadAsksForTransparentPng() {
        var payload = OpenAiImageClient.buildPayload("gpt-image-1", "a leaf");
        assertThat(payload.get("model")).isEqualTo("gpt-image-1");
        assertThat(payload.get("size")).isEqualTo("1024x1024");
        assertThat(payload.get("background")).isEqualTo("transparent");
        assertThat(payload.get("output_format")).isEqualTo("png");
        assertThat(payload).doesNotContainKey("response_format");
    }

    @Test
    void opaquePayloadDropsTransparentBackground() {
        var payload = OpenAiImageClient.buildPayload("gpt-image-1", "a leaf", false);
        assertThat(payload.get("background")).isEqualTo("opaque");
    }

    @Test
    void editsUrlSitsBesideGenerations() {
        assertThat(OpenAiImageClient.editsUrl("https://api.openai.com/v1"))
                .isEqualTo("https://api.openai.com/v1/images/edits");
    }

    @Test
    void dallEPayloadUsesB64ResponseFormat() {
        var payload = OpenAiImageClient.buildPayload("dall-e-3", "a leaf");
        assertThat(payload.get("response_format")).isEqualTo("b64_json");
        assertThat(payload).doesNotContainKey("background");
    }

    @Test
    void parseReadsB64AndUsage() {
        String body = """
                {"data":[{"b64_json":"abcd","output_format":"png"}],
                 "usage":{"input_tokens":12,"output_tokens":80}}
                """;
        var image = OpenAiImageClient.parse(body, "gpt-image-1");
        assertThat(image.base64()).isEqualTo("abcd");
        assertThat(image.mimeType()).isEqualTo("image/png");
        assertThat(image.promptTokens()).isEqualTo(12);
        assertThat(image.completionTokens()).isEqualTo(80);
    }

    @Test
    void parseRejectsEmptyData() {
        assertThatThrownBy(() -> OpenAiImageClient.parse("{\"data\":[]}", "gpt-image-1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no image");
    }

    @Test
    void contentPolicyBecomesAClearError() {
        String msg = OpenAiImageClient.userFacingImageError(400, "{\"error\":{\"code\":\"content_policy_violation\"}}");
        assertThat(msg).contains("simpler shop mark");
    }
}
