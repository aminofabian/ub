package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import zelisline.ub.desktop.application.DesktopMessagePushService.MessagePushResult;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.domain.ContactReplyChannel;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;

/**
 * Desktop → cloud "up" message relay: replies queued on the till
 * ({@code outcome=queued}) are flushed to the shop's online instance, which
 * sends them through its providers, and the ack's outcome overwrites the local
 * row while {@code cloud_synced_at} is stamped so a reply never re-sends.
 */
class DesktopMessagePushServiceTest {

    private static final String LOCAL_BUSINESS = "local-biz";
    private static final String CLOUD_ORIGIN = "https://shop.example.com";

    private final ContactMessageReplyRepository replyRepository = mock(ContactMessageReplyRepository.class);
    private final CloudSyncSession cloudSyncSession = mock(CloudSyncSession.class);

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer server;

    private DesktopMessagePushService service;

    @BeforeEach
    void setUp() {
        when(cloudSyncSession.load()).thenReturn(Optional.of(new CloudSyncSession.Session(
            CLOUD_ORIGIN, "cloud-biz", "access-token", "refresh-token",
            "owner-id", List.of("staff-1"), Instant.EPOCH, null, null, null)));

        service = new DesktopMessagePushService(
            replyRepository, cloudSyncSession, restClientBuilder);
        ReflectionTestUtils.setField(service, "desktopBusinessId", LOCAL_BUSINESS);

        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private static ContactMessageReply reply(String id, String messageId) {
        ContactMessageReply r = new ContactMessageReply();
        r.setId(id);
        r.setContactMessageId(messageId);
        r.setChannel(ContactReplyChannel.WHATSAPP);
        r.setBody("On it — we'll have it ready this afternoon.");
        r.setOutcome("queued");
        r.setSentByUserId("till-user-1");
        r.setCreatedAt(Instant.parse("2026-08-20T10:00:00Z"));
        return r;
    }

    @Test
    void pushesQueuedReplyAndAppliesAck() {
        ContactMessageReply queued = reply("r-1", "m-1");
        when(replyRepository.findQueuedForDesktopSync(anyString(), any(Pageable.class)))
            .thenReturn(List.of(queued));
        when(replyRepository.findById("r-1")).thenReturn(Optional.of(queued));

        server.expect(requestTo(CLOUD_ORIGIN + "/api/v1/desktop/sync/message-replies"))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer access-token"))
            .andExpect(header("X-Tenant-Id", "cloud-biz"))
            .andRespond(withSuccess(
                "{\"results\":[{\"replyId\":\"r-1\",\"outcome\":\"sent\",\"detail\":\"whatsapp:wa\","
                    + "\"createdAt\":\"2026-08-20T10:00:00Z\"}]}",
                MediaType.APPLICATION_JSON));

        MessagePushResult result = service.pushPendingReplies();

        server.verify();
        assertEquals(1, result.repliesPushed());
        assertTrue(result.configured());
        verify(replyRepository).save(org.mockito.ArgumentMatchers.argThat(saved -> {
            ContactMessageReply r = saved;
            return "sent".equals(r.getOutcome())
                && "whatsapp:wa".equals(r.getDetail())
                && r.getCloudSyncedAt() != null;
        }));
    }

    @Test
    void failedAckConvergesLocalRowInsteadOfStayingQueued() {
        ContactMessageReply queued = reply("r-1", "m-1");
        when(replyRepository.findQueuedForDesktopSync(anyString(), any(Pageable.class)))
            .thenReturn(List.of(queued));
        when(replyRepository.findById("r-1")).thenReturn(Optional.of(queued));

        server.expect(requestTo(CLOUD_ORIGIN + "/api/v1/desktop/sync/message-replies"))
            .andRespond(withSuccess(
                "{\"results\":[{\"replyId\":\"r-1\",\"outcome\":\"failed\","
                    + "\"detail\":\"SMS is not configured for this inbox\"}]}",
                MediaType.APPLICATION_JSON));

        MessagePushResult result = service.pushPendingReplies();

        server.verify();
        assertEquals(1, result.repliesPushed());
        // A terminal failure is still acknowledged — the thread shows the reason
        // and the row never re-sends on the next flush.
        verify(replyRepository).save(org.mockito.ArgumentMatchers.argThat(saved -> {
            ContactMessageReply r = saved;
            return "failed".equals(r.getOutcome())
                && "SMS is not configured for this inbox".equals(r.getDetail())
                && r.getCloudSyncedAt() != null;
        }));
    }

    @Test
    void noCloudMappingSkipsPush() {
        when(cloudSyncSession.load()).thenReturn(Optional.empty());

        MessagePushResult result = service.pushPendingReplies();

        assertFalse(result.configured());
        assertEquals(0, result.repliesPushed());
        verify(replyRepository, never()).findQueuedForDesktopSync(anyString(), any(Pageable.class));
    }

    @Test
    void nothingQueuedIsAnEmptySuccess() {
        when(replyRepository.findQueuedForDesktopSync(anyString(), any(Pageable.class)))
            .thenReturn(List.of());

        MessagePushResult result = service.pushPendingReplies();

        assertTrue(result.configured());
        assertEquals(0, result.repliesPushed());
    }
}
