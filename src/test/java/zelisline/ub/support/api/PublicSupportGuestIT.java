package zelisline.ub.support.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.credits.domain.CustomerPhoneNormalizer;
import zelisline.ub.credits.domain.CustomerPhoneVerification;
import zelisline.ub.credits.repository.CustomerPhoneVerificationRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.support.repository.SupportConversationRepository;
import zelisline.ub.support.repository.SupportMessageRepository;

/**
 * Regression coverage for guest-thread ownership and OTP recovery.
 *
 * <p>The guest token is the only credential that opens a transcript. A phone
 * number is contact metadata, never proof of ownership; before this test suite
 * existed, presenting someone else's phone re-claimed their thread, rotated the
 * secret, and returned the whole conversation. Recovery now requires a
 * platform-verified phone, and merges the checkout's order-card thread into the
 * buyer's own thread instead of leaving two halves of one person.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class PublicSupportGuestIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SupportMessageRepository supportMessageRepository;

    @Autowired
    private SupportConversationRepository conversationRepository;

    @Autowired
    private CustomerPhoneVerificationRepository verificationRepository;

    @Test
    void rejectsAGuestIdLongerThanTheColumnContract() throws Exception {
        String body = """
                {"type":"VISITOR","guestId":"%s","guestName":"Amani"}
                """.formatted("a".repeat(37));

        mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_PHONE, "0712345678")
                        .content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void aPhoneNumberDoesNotOpenAnotherDevicesTranscript() throws Exception {
        String ownerGuestId = "11111111-1111-4111-8111-111111111111";
        String otherGuestId = "22222222-2222-4222-8222-222222222222";
        String phone = "0712345678";

        MvcResult created = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_PHONE, phone)
                        .content("""
                                {"type":"VISITOR","guestId":"%s","guestName":"Amani"}
                                """.formatted(ownerGuestId)))
                .andExpect(status().isCreated())
                .andReturn();

        String ownerToken = JsonPath.read(created.getResponse().getContentAsString(), "$.token");
        String ownerConversationId =
                JsonPath.read(created.getResponse().getContentAsString(), "$.conversation.id");
        assertThat(ownerToken).isNotBlank();

        // A different device (different guest id) presenting the same phone must
        // not be handed the original transcript.
        MvcResult attacker = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_PHONE, phone)
                        .content("""
                                {"type":"VISITOR","guestId":"%s","guestName":"Amani"}
                                """.formatted(otherGuestId)))
                .andExpect(status().isCreated())
                .andReturn();

        String attackerConversationId =
                JsonPath.read(attacker.getResponse().getContentAsString(), "$.conversation.id");
        assertThat(attackerConversationId).isNotEqualTo(ownerConversationId);

        // And the original thread still refuses a token-less resume, even with
        // the owner's phone presented.
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", ownerGuestId)
                        .header(PublicSupportController.HEADER_GUEST_PHONE, phone))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void retryingWithTheSameClientMessageIdDoesNotDuplicate() throws Exception {
        String guestId = "44444444-4444-4444-8444-444444444444";

        MvcResult created = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_PHONE, "0734567890")
                        .content("""
                                {"type":"VISITOR","guestId":"%s","guestName":"Achieng"}
                                """.formatted(guestId)))
                .andExpect(status().isCreated())
                .andReturn();

        String token = JsonPath.read(created.getResponse().getContentAsString(), "$.token");
        String conversationId =
                JsonPath.read(created.getResponse().getContentAsString(), "$.conversation.id");
        long before = supportMessageRepository.countByConversationId(conversationId);

        String payload = """
                {"body":"Is my order ready?","clientMessageId":"retry-1"}
                """;

        MvcResult first = mockMvc.perform(post("/api/v1/public/support/threads/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_ID, guestId)
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, token)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        MvcResult second = mockMvc.perform(post("/api/v1/public/support/threads/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_ID, guestId)
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, token)
                        .content(payload))
                .andExpect(status().isCreated())
                .andReturn();

        String firstId = JsonPath.read(first.getResponse().getContentAsString(), "$.id");
        String secondId = JsonPath.read(second.getResponse().getContentAsString(), "$.id");
        assertThat(secondId).isEqualTo(firstId);
        assertThat(supportMessageRepository.countByConversationId(conversationId))
                .isEqualTo(before + 1);
    }

    @Test
    void aValidTokenCanStillResumeTheThread() throws Exception {
        String guestId = "33333333-3333-4333-8333-333333333333";

        MvcResult created = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_PHONE, "0723456789")
                        .content("""
                                {"type":"VISITOR","guestId":"%s","guestName":"Wanjiru"}
                                """.formatted(guestId)))
                .andExpect(status().isCreated())
                .andReturn();

        String token = JsonPath.read(created.getResponse().getContentAsString(), "$.token");

        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", guestId)
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, token))
                .andExpect(status().isOk());
    }

    // ── OTP recovery ────────────────────────────────────────────────────────

    @Test
    void recoveryIsRejectedWithoutAVerifiedPhoneToken() throws Exception {
        String guestId = "55555555-5555-4555-8555-555555555555";
        String phone = "0712000111";

        mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_PHONE, phone)
                        .content("""
                                {"type":"VISITOR","guestId":"%s","guestName":"Otieno"}
                                """.formatted(guestId)))
                .andExpect(status().isCreated());

        // A typed phone number is still not a credential — only the OTP is.
        mockMvc.perform(post("/api/v1/public/support/threads/recover")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type":"VISITOR","guestId":"%s","phone":"%s","phoneVerificationToken":"not-a-real-token"}
                                """.formatted(guestId, phone)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void recoveryOfAnUnknownNumberCreatesNothing() throws Exception {
        String token = mintVerificationToken("0712999888");
        String body = """
                {"type":"VISITOR","guestId":"66666666-6666-4666-8666-666666666666",
                 "phone":"0712999888","phoneVerificationToken":"%s"}
                """.formatted(token);

        mockMvc.perform(post("/api/v1/public/support/threads/recover")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());

        // A miss must not burn the single-use token: the visitor may have typed
        // the wrong number and needs to correct it, not request a second SMS.
        mockMvc.perform(post("/api/v1/public/support/threads/recover")
                        .contentType(APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isNotFound());
    }

    @Test
    void recoveryMergesTheOrderThreadAndTheBuyersOwnThread() throws Exception {
        String phone = "0712000222";
        String buyerGuestId = "77777777-7777-4777-8777-777777777777";
        String orderGuestId = "buyer-phone-254712000222";

        // 1. The buyer chats before ordering: a thread with no phone recorded.
        MvcResult chat = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type":"VISITOR","guestId":"%s","guestName":"Njeri"}
                                """.formatted(buyerGuestId)))
                .andExpect(status().isCreated())
                .andReturn();
        String chatConversationId = JsonPath.read(chat.getResponse().getContentAsString(), "$.conversation.id");
        String chatToken = JsonPath.read(chat.getResponse().getContentAsString(), "$.token");
        sendMessage(chatConversationId, buyerGuestId, chatToken, "Where is my order?");

        // 2. Checkout posted the order card into a phone-keyed thread instead.
        MvcResult order = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_PHONE, phone)
                        .content("""
                                {"type":"VISITOR","guestId":"%s","guestName":"Njeri"}
                                """.formatted(orderGuestId)))
                .andExpect(status().isCreated())
                .andReturn();
        String orderConversationId = JsonPath.read(order.getResponse().getContentAsString(), "$.conversation.id");
        String orderToken = JsonPath.read(order.getResponse().getContentAsString(), "$.token");
        sendMessage(orderConversationId, orderGuestId, orderToken, "New online order ORD-1001");
        assertThat(orderConversationId).isNotEqualTo(chatConversationId);

        // 3. The visitor proves the phone and recovers.
        MvcResult recovered = mockMvc.perform(post("/api/v1/public/support/threads/recover")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type":"VISITOR","guestId":"%s","phone":"%s","phoneVerificationToken":"%s"}
                                """.formatted(buyerGuestId, phone, mintVerificationToken(phone))))
                .andExpect(status().isOk())
                .andReturn();

        String mergedId = JsonPath.read(recovered.getResponse().getContentAsString(), "$.conversation.id");
        String mergedToken = JsonPath.read(recovered.getResponse().getContentAsString(), "$.token");
        List<String> bodies = JsonPath.read(recovered.getResponse().getContentAsString(), "$.messages[*].body");
        assertThat(mergedId).isEqualTo(orderConversationId);
        assertThat(bodies).contains("Where is my order?", "New online order ORD-1001");
        assertThat(mergedToken).isNotBlank();

        // Both halves now live on one thread; the emptied shell is gone.
        assertThat(supportMessageRepository.countByConversationId(orderConversationId)).isEqualTo(2);
        assertThat(conversationRepository.findById(chatConversationId)).isEmpty();

        // The browser id opens the merged thread with the minted secret…
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", buyerGuestId)
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, mergedToken))
                .andExpect(status().isOk());

        // …and the two superseded credentials open nothing.
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", buyerGuestId)
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, chatToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", orderGuestId)
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, orderToken))
                .andExpect(status().isNotFound());
    }

    private void sendMessage(String conversationId, String guestId, String token, String body)
            throws Exception {
        mockMvc.perform(post("/api/v1/public/support/threads/" + conversationId + "/messages")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_ID, guestId)
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, token)
                        .content("""
                                {"body":"%s"}
                                """.formatted(body)))
                .andExpect(status().isCreated());
    }

    /** Seeds a platform OTP challenge already verified, plus a single-use token. */
    private String mintVerificationToken(String phone) {
        String token = "test-verify-" + UUID.randomUUID();
        Instant now = Instant.now();
        CustomerPhoneVerification challenge = new CustomerPhoneVerification();
        challenge.setBusinessId("platform");
        challenge.setPhone(CustomerPhoneNormalizer.normalize(phone));
        challenge.setCodeHash(TokenHasher.sha256Hex("1234"));
        challenge.setExpiresAt(now.plus(Duration.ofMinutes(10)));
        challenge.setAttempts(0);
        challenge.setMaxAttempts(5);
        challenge.setLastSentAt(now);
        challenge.setVerifiedAt(now);
        challenge.setRegistrationTokenHash(TokenHasher.sha256Hex(token));
        challenge.setRegistrationTokenExpiresAt(now.plus(Duration.ofMinutes(15)));
        verificationRepository.save(challenge);
        return token;
    }
}
