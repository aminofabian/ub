package zelisline.ub.messaging.infrastructure;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class MetaWhatsAppMessagingClientTest {

    @Test
    void languageCandidatesPreferEnUs() {
        List<String> langs = MetaWhatsAppMessagingClient.languageCandidates("en");
        assertEquals("en", langs.get(0));
        assertTrue(langs.contains("en_US"));
    }

    @Test
    void defaultLanguageIsEnUs() {
        assertEquals("en_US", MetaWhatsAppMessagingClient.languageCandidates(null).get(0));
    }

    @Test
    void urlButtonParameterStripsHost() {
        assertEquals(
                "t/254712345678",
                MetaWhatsAppMessagingClient.urlButtonParameter("https://palmart.co.ke/t/254712345678"));
    }

    @Test
    void templateCandidatesIncludeBodyAndUrlButtonShapes() {
        List<Map<String, Object>> shapes = MetaWhatsAppMessagingClient.templatePayloadCandidates(
                "payment_reminder",
                "en_US",
                List.of("Ada", "KES 100.00", "https://palmart.co.ke/t/2547"));
        assertTrue(shapes.size() >= 3);
        assertTrue(shapes.get(0).containsKey("components"));
    }

    @Test
    void retryableTemplateErrorsDetected() {
        assertTrue(MetaWhatsAppMessagingClient.looksLikeRetryableTemplateError(
                "http_400: OAuthException — Template name does not exist in the translation [code=132001]"));
        assertTrue(MetaWhatsAppMessagingClient.looksLikeRetryableTemplateError(
                "http_400: (#132000) Number of parameters does not match"));
        assertFalse(MetaWhatsAppMessagingClient.looksLikeRetryableTemplateError("http_429: rate limited"));
    }

    @Test
    void parseMetaErrorIncludesCodes() {
        String detail = MetaWhatsAppMessagingClient.formatHttpFailure(
                400,
                "{\"error\":{\"message\":\"Template name does not exist in the translation\",\"type\":\"OAuthException\",\"code\":132001,\"error_subcode\":0}}");
        assertTrue(detail.contains("132001"));
        assertTrue(detail.contains("Template name"));
    }
}
