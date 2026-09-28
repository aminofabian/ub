package zelisline.ub.desktop.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import zelisline.ub.audit.domain.AuditEvent;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.audit.repository.AuditEventRepository;

/**
 * Till → cloud audit-event forwarding: pending local events are uploaded with a
 * {@code (createdAt, id)} cursor, and the cursor only advances after the cloud
 * acknowledges. First run seeds the cursor to "now" rather than dumping history.
 */
class DesktopAuditPushServiceTest {

    private static final String LOCAL_BUSINESS = "local-biz";
    private static final String CLOUD_ORIGIN = "https://shop.example.com";
    private static final Instant CURSOR_AT = Instant.parse("2026-09-28T09:00:00Z");
    private static final String AUDIT_URI = CLOUD_ORIGIN + "/api/v1/desktop/sync/audit-events";

    private final AuditEventRepository auditEventRepository = mock(AuditEventRepository.class);
    private final CloudSyncSession cloudSyncSession = mock(CloudSyncSession.class);
    private final DesktopAuditCursor auditCursor = mock(DesktopAuditCursor.class);

    private final RestClient.Builder restClientBuilder = RestClient.builder();
    private MockRestServiceServer server;

    private DesktopAuditPushService service;

    @BeforeEach
    void setUp() {
        when(cloudSyncSession.load()).thenReturn(Optional.of(new CloudSyncSession.Session(
            CLOUD_ORIGIN, "cloud-biz", "access-token", "refresh-token",
            "owner-id", List.of("staff-1"), Instant.EPOCH, null, null, null, null)));
        service = new DesktopAuditPushService(
            auditEventRepository, cloudSyncSession, auditCursor, restClientBuilder);
        ReflectionTestUtils.setField(service, "desktopBusinessId", LOCAL_BUSINESS);
        server = MockRestServiceServer.bindTo(restClientBuilder).build();
    }

    private static AuditEvent event(String id, Instant createdAt) {
        AuditEvent e = new AuditEvent();
        e.setId(id);
        e.setBusinessId(LOCAL_BUSINESS);
        e.setCategory(AuditEventCategory.SALES);
        e.setEventType("sale.completed");
        e.setSeverity(AuditEventSeverity.INFO);
        e.setActorType(AuditEventActorType.SYSTEM);
        e.setSource("desktop_sync");
        e.setCreatedAt(createdAt);
        return e;
    }

    @Test
    void forwardsEventsAndAdvancesCursorToTheLastEvent() {
        when(auditCursor.load()).thenReturn(Optional.of(
            new DesktopAuditCursor.Cursor(CURSOR_AT, "")));
        AuditEvent first = event("e1", Instant.parse("2026-09-28T09:30:00Z"));
        AuditEvent last = event("e2", Instant.parse("2026-09-28T09:31:00Z"));
        when(auditEventRepository.findAfterCursor(eq(LOCAL_BUSINESS), eq(CURSOR_AT), eq(""), any()))
            .thenReturn(List.of(first, last));
        server.expect(requestTo(AUDIT_URI))
            .andExpect(method(HttpMethod.POST))
            .andExpect(header("Authorization", "Bearer access-token"))
            .andRespond(withSuccess("{\"ingested\":2,\"skipped\":0}", MediaType.APPLICATION_JSON));

        int ingested = service.pushPendingAudits();

        assertEquals(2, ingested);
        server.verify();
        ArgumentCaptor<DesktopAuditCursor.Cursor> saved =
            ArgumentCaptor.forClass(DesktopAuditCursor.Cursor.class);
        verify(auditCursor).save(saved.capture());
        assertEquals(last.getCreatedAt(), saved.getValue().createdAt());
        assertEquals("e2", saved.getValue().id());
    }

    @Test
    void firstRunBackfillsFromEpoch() {
        when(auditCursor.load()).thenReturn(Optional.empty());

        int ingested = service.pushPendingAudits();

        // Nothing to push (repo mock returns empty), but the query starts at epoch.
        assertEquals(0, ingested);
        verify(auditEventRepository).findAfterCursor(
            eq(LOCAL_BUSINESS), eq(Instant.EPOCH), eq(""), any());
        // No seed is persisted while there is nothing to advance past.
        verify(auditCursor, never()).save(any());
        server.verify();
    }

    @Test
    void firstRunForwardsExistingHistory() {
        when(auditCursor.load()).thenReturn(Optional.empty());
        AuditEvent old = event("old-1", Instant.parse("2024-01-01T00:00:00Z"));
        when(auditEventRepository.findAfterCursor(
                eq(LOCAL_BUSINESS), eq(Instant.EPOCH), eq(""), any()))
            .thenReturn(List.of(old));
        server.expect(requestTo(AUDIT_URI))
            .andRespond(withSuccess("{\"ingested\":1,\"skipped\":0}", MediaType.APPLICATION_JSON));

        int ingested = service.pushPendingAudits();

        assertEquals(1, ingested);
        ArgumentCaptor<DesktopAuditCursor.Cursor> saved =
            ArgumentCaptor.forClass(DesktopAuditCursor.Cursor.class);
        verify(auditCursor).save(saved.capture());
        assertEquals("old-1", saved.getValue().id());
        assertEquals(old.getCreatedAt(), saved.getValue().createdAt());
    }

    @Test
    void emptyBatchDoesNotCallTheCloud() {
        when(auditCursor.load()).thenReturn(Optional.of(
            new DesktopAuditCursor.Cursor(CURSOR_AT, "e9")));
        when(auditEventRepository.findAfterCursor(eq(LOCAL_BUSINESS), any(), any(), any()))
            .thenReturn(List.of());

        int ingested = service.pushPendingAudits();

        assertEquals(0, ingested);
        server.verify();
    }

    @Test
    void nothingHappensWhenTheTillIsNotConnected() {
        when(cloudSyncSession.load()).thenReturn(Optional.empty());

        int ingested = service.pushPendingAudits();

        assertEquals(0, ingested);
        server.verify();
    }
}
