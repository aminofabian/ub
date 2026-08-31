package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;
import zelisline.ub.desktop.api.dto.MessageSyncSnapshot;
import zelisline.ub.desktop.application.DesktopMessagePullService.MessagePullResult;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.domain.ContactMessageStatus;
import zelisline.ub.messages.domain.ContactReplyChannel;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;
import zelisline.ub.messages.repository.ContactMessageRepository;

/**
 * Cloud → till "down" message relay: the shop's Talk to Us messages (each with
 * its full reply thread) are mirrored into the local inbox, the cursor advances
 * to the newest activity timestamp seen, and local state (a till-side READ, a
 * queued reply) is never clobbered.
 */
class DesktopMessagePullServiceTest {

    private static final String LOCAL_BUSINESS = "local-biz";
    private static final String CLOUD_ORIGIN = "https://shop.example.com";

    private final ContactMessageRepository messageRepository = mock(ContactMessageRepository.class);
    private final ContactMessageReplyRepository replyRepository = mock(ContactMessageReplyRepository.class);
    private final CloudSyncSession cloudSyncSession = mock(CloudSyncSession.class);
    private final TransactionTemplate transactionTemplate = mock(TransactionTemplate.class);
    private final ObjectMapper objectMapper = new ObjectMapper()
        .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer server;

    private DesktopMessagePullService service;

    @BeforeEach
    void setUp() {
        // The service wraps its writes in a TransactionTemplate; run the callback
        // inline so the mocked repos see the inserts.
        when(transactionTemplate.execute(any(TransactionCallback.class))).thenAnswer(inv ->
            inv.getArgument(0, TransactionCallback.class)
                .doInTransaction(mock(TransactionStatus.class)));

        when(cloudSyncSession.load()).thenReturn(Optional.of(new CloudSyncSession.Session(
            CLOUD_ORIGIN, "cloud-biz", "access-token", "refresh-token",
            "owner-id", List.of("staff-1"), null, null)));

        service = new DesktopMessagePullService(
            messageRepository, replyRepository, cloudSyncSession,
            transactionTemplate, restClientBuilder);
        ReflectionTestUtils.setField(service, "desktopBusinessId", LOCAL_BUSINESS);

        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private void expectMessagesRequest(String since, String json) {
        server.expect(requestTo(CLOUD_ORIGIN + "/api/v1/desktop/sync/messages?since=" + since))
            .andExpect(method(HttpMethod.GET))
            .andExpect(header("Authorization", "Bearer access-token"))
            .andExpect(header("X-Tenant-Id", "cloud-biz"))
            .andRespond(withSuccess(json, MediaType.APPLICATION_JSON));
    }

    private String snapshotJson(List<MessageSyncSnapshot.MessageSyncData> messages) throws Exception {
        return objectMapper.writeValueAsString(new MessageSyncSnapshot(messages));
    }

    private static MessageSyncSnapshot.MessageSyncData messageData(
            String id, String status, String replyId, String replyCreatedAt) {
        List<MessageSyncSnapshot.ReplySyncData> replies = replyId == null
            ? List.of()
            : List.of(new MessageSyncSnapshot.ReplySyncData(
                replyId, "WHATSAPP", "On it", "sent", "whatsapp:wa", "cloud-user-1",
                Instant.parse(replyCreatedAt)));
        return new MessageSyncSnapshot.MessageSyncData(
            id, "Jane Shopper", "jane@example.com", "0712345678",
            "Do you stock maize flour?", status, null, null,
            Instant.parse("2026-08-20T10:00:00Z"), replies);
    }

    @Test
    void mirrorsCloudMessagesWithTheirReplyThreads() throws Exception {
        expectMessagesRequest("1970-01-01T00:00:00Z",
            snapshotJson(List.of(messageData("m-1", "UNREAD", "r-1", "2026-08-20T10:10:00Z"))));

        MessagePullResult result = service.pullMessages();

        server.verify();
        assertEquals(1, result.messages());
        assertEquals(1, result.replies());
        verify(messageRepository).save(org.mockito.ArgumentMatchers.argThat(m -> {
            ContactMessage saved = m;
            return "m-1".equals(saved.getId())
                && saved.getScope() == ContactMessageScope.TENANT
                && LOCAL_BUSINESS.equals(saved.getBusinessId())
                && saved.getStatus() == ContactMessageStatus.UNREAD
                && saved.getCreatedAt().equals(Instant.parse("2026-08-20T10:00:00Z"));
        }));
        verify(replyRepository).save(org.mockito.ArgumentMatchers.argThat(r -> {
            ContactMessageReply saved = r;
            return "r-1".equals(saved.getId())
                && "m-1".equals(saved.getContactMessageId())
                && saved.getChannel() == ContactReplyChannel.WHATSAPP
                && "sent".equals(saved.getOutcome());
        }));
        // Cursor advances to the newest activity (the reply's creation), not
        // just the message's creation.
        verify(cloudSyncSession).persistLastMessagesPullAt(
            any(), org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-20T10:10:00Z")));
    }

    @Test
    void localReadIsNotDowngradedWhenCloudSaysUnread() throws Exception {
        ContactMessage local = new ContactMessage();
        local.setId("m-1");
        local.setName("Jane Shopper");
        local.setEmail("jane@example.com");
        local.setPhone("0712345678");
        local.setBody("Do you stock maize flour?");
        local.setStatus(ContactMessageStatus.READ);
        local.setReadAt(Instant.parse("2026-08-20T11:00:00Z"));
        when(messageRepository.findById("m-1")).thenReturn(Optional.of(local));
        expectMessagesRequest("1970-01-01T00:00:00Z",
            snapshotJson(List.of(messageData("m-1", "UNREAD", null, null))));

        MessagePullResult result = service.pullMessages();

        server.verify();
        assertEquals(0, result.messages());
        // The local READ is preserved: nothing re-saved.
        verify(messageRepository, never()).save(any(ContactMessage.class));
        verify(cloudSyncSession).persistLastMessagesPullAt(
            any(), org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-20T10:00:00Z")));
    }

    @Test
    void existingQueuedReplyIsNotClobberedByThePull() throws Exception {
        // The till queued r-1 locally (outcome=queued); the cloud thread now
        // shows it as sent. The push ack is what converges this — the pull must
        // leave the local row alone.
        when(messageRepository.findById("m-1")).thenReturn(Optional.of(mock(ContactMessage.class)));
        when(replyRepository.existsById("r-1")).thenReturn(true);
        expectMessagesRequest("1970-01-01T00:00:00Z",
            snapshotJson(List.of(messageData("m-1", "READ", "r-1", "2026-08-20T10:10:00Z"))));

        MessagePullResult result = service.pullMessages();

        server.verify();
        assertEquals(0, result.replies());
        verify(replyRepository, never()).save(any(ContactMessageReply.class));
    }

    @Test
    void pullsInPagesUntilAShortPage() throws Exception {
        List<MessageSyncSnapshot.MessageSyncData> pageOne = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            pageOne.add(messageData("m-" + i, "UNREAD", null, null));
        }
        expectMessagesRequest("1970-01-01T00:00:00Z", snapshotJson(pageOne));
        expectMessagesRequest("2026-08-20T10:00:00Z",
            snapshotJson(List.of(messageData("m-100", "UNREAD", null, null))));

        MessagePullResult result = service.pullMessages();

        server.verify();
        assertEquals(101, result.messages());
    }

    @Test
    void noCloudMessagesIsAQuietNoOp() throws Exception {
        expectMessagesRequest("1970-01-01T00:00:00Z", "{\"messages\":[]}");

        MessagePullResult result = service.pullMessages();

        server.verify();
        assertEquals(0, result.messages());
        assertEquals(0, result.replies());
        verify(messageRepository, never()).save(any(ContactMessage.class));
        verify(cloudSyncSession, never()).persistLastMessagesPullAt(any(), any());
    }

    @Test
    void notConnectedThrowsConflict() {
        when(cloudSyncSession.load()).thenReturn(Optional.empty());

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> service.pullMessages());

        assertEquals(HttpStatus.CONFLICT, ex.getStatusCode());
    }

    @Test
    void idempotentOnAlreadyMirroredMessage() throws Exception {
        // A message already mirrored (display fields unchanged) is not re-saved,
        // but the cursor still advances past it.
        ContactMessage local = new ContactMessage();
        local.setId("m-1");
        local.setStatus(ContactMessageStatus.UNREAD);
        local.setName("Jane Shopper");
        local.setEmail("jane@example.com");
        local.setPhone("0712345678");
        local.setBody("Do you stock maize flour?");
        local.setSourcePath(null);
        when(messageRepository.findById("m-1")).thenReturn(Optional.of(local));
        expectMessagesRequest("1970-01-01T00:00:00Z",
            snapshotJson(List.of(messageData("m-1", "UNREAD", null, null))));

        MessagePullResult result = service.pullMessages();

        server.verify();
        assertEquals(0, result.messages());
        verify(messageRepository, never()).save(any(ContactMessage.class));
        verify(cloudSyncSession).persistLastMessagesPullAt(
            any(), org.mockito.ArgumentMatchers.eq(Instant.parse("2026-08-20T10:00:00Z")));
    }
}
