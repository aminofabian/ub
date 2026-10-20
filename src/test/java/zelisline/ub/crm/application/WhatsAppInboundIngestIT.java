package zelisline.ub.crm.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import zelisline.ub.crm.repository.CrmContactRepository;
import zelisline.ub.crm.repository.CrmConversationRepository;
import zelisline.ub.crm.repository.CrmMessageRepository;
import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRoute;
import zelisline.ub.integrations.whatsapp.domain.WhatsAppChannelRouteStatuses;
import zelisline.ub.integrations.whatsapp.repository.WhatsAppChannelRouteRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * End-to-end: a signed Meta inbound message must land in the CRM inbox (contact + conversation +
 * message). Proves the whole {@code /webhooks/whatsapp → InboundIngest} path when the channel is on
 * and the number is routed — so a live "inbox empty" points at Meta subscription/config, not code.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.messaging.meta-whatsapp.app-secret=test-app-secret",
        "app.messaging.meta-whatsapp.webhook-verify-token=test-verify",
        "app.integrations.whatsapp.enabled=true",
})
class WhatsAppInboundIngestIT {

    private static final String APP_SECRET = "test-app-secret";
    private static final String TENANT = "11111111-1111-1111-1111-111111111111";
    private static final String PHONE_NUMBER_ID = "1252977897893339";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private BusinessRepository businessRepository;
    @Autowired
    private WhatsAppChannelRouteRepository routeRepository;
    @Autowired
    private CrmConversationRepository conversationRepository;
    @Autowired
    private CrmMessageRepository messageRepository;
    @Autowired
    private CrmContactRepository contactRepository;
    @Autowired
    private CrmConversationService conversationService;

    @Test
    void signedInboundMessageCreatesConversationAndMessage() throws Exception {
        seedRoutedBusiness();

        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA1","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"254700000000","phone_number_id":"%s"},"contacts":[{"profile":{"name":"Test Customer"},"wa_id":"254711111111"}],"messages":[{"from":"254711111111","id":"wamid.TESTINBOUND1","timestamp":"1700000000","type":"text","text":{"body":"Hello shop"}}]}}]}]}
                """.formatted(PHONE_NUMBER_ID).trim();

        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + hmac(body))
                        .content(body))
                .andExpect(status().isOk());

        assertThat(contactRepository.findAll()).hasSize(1);
        assertThat(conversationRepository.findAll()).hasSize(1);
        assertThat(messageRepository.findAll()).hasSize(1);
        assertThat(conversationRepository.findAll().get(0).getBusinessId()).isEqualTo(TENANT);

        var list = conversationService.list(TENANT, null, null, 0, 20);
        assertThat(list.items()).hasSize(1);
        assertThat(list.items().get(0).lastMessagePreview()).isEqualTo("Hello shop");
        assertThat(list.items().get(0).lastMessageType()).isEqualTo("text");
        assertThat(list.items().get(0).lastMessageDirection()).isEqualTo("inbound");
    }

    @Test
    void inboundMessageForUnroutedNumberCreatesNoConversation() throws Exception {
        seedRoutedBusiness();

        String body = """
                {"object":"whatsapp_business_account","entry":[{"id":"WABA1","changes":[{"field":"messages","value":{"messaging_product":"whatsapp","metadata":{"display_phone_number":"254700000000","phone_number_id":"999999999999999"},"contacts":[{"profile":{"name":"Test Customer"},"wa_id":"254711111111"}],"messages":[{"from":"254711111111","id":"wamid.TESTUNROUTED1","timestamp":"1700000000","type":"text","text":{"body":"Hi"}}]}}]}]}
                """.trim();

        mockMvc.perform(post("/webhooks/whatsapp")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", "sha256=" + hmac(body))
                        .content(body))
                .andExpect(status().isOk());

        assertThat(conversationRepository.findAll()).isEmpty();
    }

    private void seedRoutedBusiness() {
        messageRepository.deleteAll();
        conversationRepository.deleteAll();
        contactRepository.deleteAll();
        routeRepository.deleteAll();
        businessRepository.deleteAll();

        Business business = new Business();
        business.setId(TENANT);
        business.setName("Inbox Co");
        business.setSlug("inbox-co");
        business.setSettings("{}");
        businessRepository.save(business);

        WhatsAppChannelRoute route = new WhatsAppChannelRoute();
        route.setPhoneNumberId(PHONE_NUMBER_ID);
        route.setBusinessId(TENANT);
        route.setStatus(WhatsAppChannelRouteStatuses.ACTIVE);
        routeRepository.save(route);
    }

    private static String hmac(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(APP_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
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
