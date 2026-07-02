package zelisline.ub.messaging.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.tenancy.repository.DomainMappingRepository;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.messaging.meta-whatsapp.webhook-verify-token=test-webhook-verify-token",
        "app.messaging.meta-whatsapp.app-secret=test-app-secret",
})
class MetaWhatsAppWebhookIT {

    private static final String VERIFY_TOKEN = "test-webhook-verify-token";
    private static final String APP_SECRET = "test-app-secret";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    @SuppressWarnings("unused")
    private DomainMappingRepository domainMappingRepository;

    @Test
    void verifySubscriptionReturnsChallenge() throws Exception {
        mockMvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", VERIFY_TOKEN)
                        .param("hub.challenge", "1234567890"))
                .andExpect(status().isOk())
                .andExpect(content().string("1234567890"));
    }

    @Test
    void verifySubscriptionRejectsWrongToken() throws Exception {
        mockMvc.perform(get("/webhooks/whatsapp")
                        .param("hub.mode", "subscribe")
                        .param("hub.verify_token", "wrong")
                        .param("hub.challenge", "1234567890"))
                .andExpect(status().isForbidden());
    }

    @Test
    void receiveEventAcceptsValidSignature() throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[]}
                """.trim();
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + hmac(body))
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(content().string("EVENT_RECEIVED"));
    }

    @Test
    void receiveEventRejectsInvalidSignature() throws Exception {
        String body = """
                {"object":"whatsapp_business_account","entry":[]}
                """.trim();
        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=deadbeef")
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private static String hmac(String payload) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(
                    APP_SECRET.getBytes(java.nio.charset.StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
