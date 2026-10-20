package zelisline.ub.support.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

import zelisline.ub.support.repository.SupportMessageRepository;

/**
 * Regression coverage for guest-thread ownership.
 *
 * <p>The guest token is the only credential that opens a transcript. A phone
 * number is contact metadata, never proof of ownership; before this test suite
 * existed, presenting someone else's phone re-claimed their thread, rotated the
 * secret, and returned the whole conversation.
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
}
