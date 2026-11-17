package zelisline.ub.support.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
 * Emoji reactions on support messages. Driven through the guest (VISITOR) surface
 * because it needs no authenticated tenant/admin fixture, while exercising the
 * same {@code toggleReaction} core every surface shares. Covers the toggle
 * contract, the cross-thread (and therefore cross-tenant) denial, emoji
 * validation, and that a reaction survives a thread reload with the right
 * {@code mine} flag.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "spring.jpa.hibernate.ddl-auto=create-drop")
class SupportReactionIT {

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

    private MvcResult react(Thread t, String messageId, String emoji) throws Exception {
        return mockMvc.perform(post("/api/v1/public/support/threads/"
                        + t.conversationId() + "/messages/" + messageId + "/reactions")
                        .contentType(APPLICATION_JSON)
                        .header(PublicSupportController.HEADER_GUEST_ID, t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token())
                        .content("""
                                {"emoji":"%s"}
                                """.formatted(emoji)))
                .andReturn();
    }

    @Test
    void addingAReactionReturnsItWithCountAndMine() throws Exception {
        Thread t = startThread("aaaaaaaa-0000-4000-8000-0000000000a1", "Amani");
        String messageId = sendMessage(t, "Is this in stock?");

        MvcResult res = react(t, messageId, "👍");
        assertThat(res.getResponse().getStatus()).isEqualTo(200);
        String body = res.getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(body, "$.reactions[*].emoji")).containsExactly("👍");
        assertThat((int) JsonPath.read(body, "$.reactions[0].count")).isEqualTo(1);
        assertThat((boolean) JsonPath.read(body, "$.reactions[0].mine")).isTrue();
    }

    @Test
    void togglingTheSameEmojiTwiceRemovesIt() throws Exception {
        Thread t = startThread("aaaaaaaa-0000-4000-8000-0000000000a2", "Baraka");
        String messageId = sendMessage(t, "Do you take M-Pesa?");

        react(t, messageId, "❤️");
        MvcResult off = react(t, messageId, "❤️");

        List<Object> reactions = JsonPath.read(off.getResponse().getContentAsString(), "$.reactions");
        assertThat(reactions).isEmpty();
    }

    @Test
    void reactingToAMessageInAnotherThreadIsNotFound() throws Exception {
        Thread owner = startThread("aaaaaaaa-0000-4000-8000-0000000000a3", "Owner");
        String ownersMessage = sendMessage(owner, "My private message");

        Thread stranger = startThread("bbbbbbbb-0000-4000-8000-0000000000b3", "Stranger");
        // The stranger authenticates to their OWN thread but targets the owner's
        // message id — the conversation-ownership guard must reject it.
        MvcResult res = react(stranger, ownersMessage, "👀");
        assertThat(res.getResponse().getStatus()).isEqualTo(404);
    }

    @Test
    void aNonEmojiBodyIsRejected() throws Exception {
        Thread t = startThread("aaaaaaaa-0000-4000-8000-0000000000a4", "Chacha");
        String messageId = sendMessage(t, "Hi");

        MvcResult res = react(t, messageId, "hello");
        assertThat(res.getResponse().getStatus()).isEqualTo(400);
    }

    @Test
    void aReactionSurvivesAThreadReloadWithMineSet() throws Exception {
        Thread t = startThread("aaaaaaaa-0000-4000-8000-0000000000a5", "Dalila");
        String messageId = sendMessage(t, "Thanks!");
        react(t, messageId, "🎉");

        MvcResult reload = mockMvc.perform(get("/api/v1/public/support/threads/me")
                        .param("type", "VISITOR")
                        .param("guestId", t.guestId())
                        .header(PublicSupportController.HEADER_GUEST_TOKEN, t.token()))
                .andExpect(status().isOk())
                .andReturn();

        String body = reload.getResponse().getContentAsString();
        assertThat((List<String>) JsonPath.read(body, "$.messages[*].reactions[*].emoji"))
                .contains("🎉");
        // The message the guest reacted to shows mine=true for that emoji.
        List<Boolean> mine = JsonPath.read(body, "$.messages[*].reactions[*].mine");
        assertThat(mine).contains(true);
    }
}
