package zelisline.ub.support.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * Editing and soft-deleting (unsend) support messages. Driven through the guest
 * surface (no tenant/admin fixture needed) while exercising the shared
 * {@code editMessage}/{@code deleteMessage} core. Covers own-message-only authz,
 * the tombstone contract (body blanked, reply-quote fallback), and idempotent
 * delete.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SupportEditDeleteIT {

    @Autowired
    private MockMvc mockMvc;

    private record Thread(String conversationId, String guestId, String token) {}

    private Thread startThread(String guestId, String name) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/v1/public/support/threads")
                        .contentType(APPLICATION_JSON)
                        .content("""
                                {"type":"VISITOR","guestId":"%s","guestName":"%s"}
                                """.formatted(guestId, name)))
                .andExpect(status().isCreated())
                .andReturn();
        String body = created.getResponse().getContentAsString();
        return new Thread(JsonPath.read(body, "$.conversation.id"),
                guestId, JsonPath.read(body, "$.token"));
    }

    private String sendMessage(Thread t, String text) throws Exception {
        MvcResult res = mockMvc.perform(post(
                        "/api/v1/public/support/threads/" + t.conversationId() + "/messages")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_ID, t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token())
                        .content("""
                                {"body":"%s"}
                                """.formatted(text)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    }

    private String sendReply(Thread t, String text, String replyToId) throws Exception {
        MvcResult res = mockMvc.perform(post(
                        "/api/v1/public/support/threads/" + t.conversationId() + "/messages")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_ID, t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token())
                        .content("""
                                {"body":"%s","replyToMessageId":"%s"}
                                """.formatted(text, replyToId)))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    }

    @Test
    void editingOwnMessageUpdatesBodyAndMarksEdited() throws Exception {
        Thread t = startThread("aaaaaaaa-0000-4000-8000-0000000000e1", "Amani");
        String messageId = sendMessage(t, "Is this in stok?");

        MvcResult res = mockMvc.perform(patch(
                        "/api/v1/public/support/threads/" + t.conversationId() + "/messages/" + messageId)
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_ID, t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token())
                        .content("""
                                {"body":"Is this in stock?"}
                                """))
                .andExpect(status().isOk())
                .andReturn();

        String body = res.getResponse().getContentAsString();
        assertThat((String) JsonPath.read(body, "$.body")).isEqualTo("Is this in stock?");
        assertThat((String) JsonPath.read(body, "$.editedAt")).isNotBlank();
    }

    @Test
    void cannotEditSomeoneElsesMessage() throws Exception {
        Thread owner = startThread("aaaaaaaa-0000-4000-8000-0000000000e2", "Owner");
        String ownersMessage = sendMessage(owner, "Mine to edit");

        Thread stranger = startThread("bbbbbbbb-0000-4000-8000-0000000000e2", "Stranger");
        // A different thread can't reach another message id at all → 404 (the
        // ownership guard checks conversation membership before authorship).
        mockMvc.perform(patch(
                        "/api/v1/public/support/threads/" + stranger.conversationId() + "/messages/" + ownersMessage)
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_ID, stranger.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, stranger.token())
                        .content("""
                                {"body":"hijacked"}
                                """))
                .andExpect(status().isNotFound());
    }

    @Test
    void deletingOwnMessageTombstonesItAndBlanksTheBodyOnReload() throws Exception {
        Thread t = startThread("aaaaaaaa-0000-4000-8000-0000000000e3", "Baraka");
        String messageId = sendMessage(t, "Oops wrong chat");

        mockMvc.perform(delete(
                        "/api/v1/public/support/threads/" + t.conversationId() + "/messages/" + messageId)
                        .header(PublicSupportController.HEADER_GUEST_ID, t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token()))
                .andExpect(status().isOk());

        MvcResult reload = mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token()))
                .andExpect(status().isOk())
                .andReturn();

        String body = reload.getResponse().getContentAsString();
        // The row survives (one message), but its body is blanked and deletedAt is set.
        List<String> bodies = JsonPath.read(body, "$.messages[*].body");
        List<String> deletedAts = JsonPath.read(body, "$.messages[*].deletedAt");
        assertThat(bodies).containsExactly("");
        assertThat(deletedAts.get(0)).isNotBlank();
    }

    @Test
    void aReplyToADeletedMessageShowsADeletedPlaceholder() throws Exception {
        Thread t = startThread("aaaaaaaa-0000-4000-8000-0000000000e4", "Chacha");
        String target = sendMessage(t, "original question");
        sendReply(t, "answer to it", target);

        mockMvc.perform(delete(
                        "/api/v1/public/support/threads/" + t.conversationId() + "/messages/" + target)
                        .header(PublicSupportController.HEADER_GUEST_ID, t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token()))
                .andExpect(status().isOk());

        MvcResult reload = mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token()))
                .andExpect(status().isOk())
                .andReturn();

        List<String> replyPreviews = JsonPath.read(
                reload.getResponse().getContentAsString(), "$.messages[*].replyTo.body");
        assertThat(replyPreviews).contains("Deleted message");
    }

    @Test
    void deleteIsIdempotent() throws Exception {
        Thread t = startThread("aaaaaaaa-0000-4000-8000-0000000000e5", "Dalila");
        String messageId = sendMessage(t, "delete me twice");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(delete(
                            "/api/v1/public/support/threads/" + t.conversationId() + "/messages/" + messageId)
                            .header(PublicSupportController.HEADER_GUEST_ID, t.guestId())
                            .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token()))
                    .andExpect(status().isOk());
        }
    }
}
