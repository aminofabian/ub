package zelisline.ub.support.application;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import zelisline.ub.platform.realtime.RealtimeScopes;
import zelisline.ub.platform.realtime.RealtimeWebSocketHandler;
import zelisline.ub.platform.realtime.SessionRegistry;
import zelisline.ub.support.domain.SupportConversation;

/**
 * Fans support-chat events out to the right WebSocket sessions after commit.
 *
 * <p>Delivery is per conversation type:
 * <ul>
 *   <li>{@code TENANT} — the tenant's business sessions (channel {@code support})
 *       plus the super-admin console sessions.</li>
 *   <li>{@code VISITOR} — the super-admin console plus the guest's own sockets
 *       (channel {@code support.guest:<guestId>}).</li>
 *   <li>{@code STOREFRONT} — the tenant's business sessions plus the buyer's own
 *       sockets on their guest channel.</li>
 * </ul>
 */
@Service
public class SupportRealtimeBridge {

    private static final Logger log = LoggerFactory.getLogger(SupportRealtimeBridge.class);

    static final String CHANNEL = "support";
    static final String PRIORITY = "HIGH";

    private final SessionRegistry sessionRegistry;
    private final RealtimeWebSocketHandler handler;

    public SupportRealtimeBridge(SessionRegistry sessionRegistry, RealtimeWebSocketHandler handler) {
        this.sessionRegistry = sessionRegistry;
        this.handler = handler;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(SupportEvents.SupportMessageSentEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {"conversationId":"%s","messageId":"%s","senderType":"%s","senderUserId":"%s","senderName":"%s","body":"%s","createdAt":"%s","conversationType":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(event.conversationId()),
                RealtimeWebSocketHandler.escapeJson(event.messageId()),
                RealtimeWebSocketHandler.escapeJson(event.senderType()),
                RealtimeWebSocketHandler.escapeJson(event.senderUserId()),
                RealtimeWebSocketHandler.escapeJson(event.senderName()),
                RealtimeWebSocketHandler.escapeJson(event.body()),
                event.createdAt().toString(),
                RealtimeWebSocketHandler.escapeJson(event.conversationType()));

        fanOut(event.businessId(), event.conversationType(), event.guestId(),
                "support.message", eventId, payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagesRead(SupportEvents.SupportMessagesReadEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {"conversationId":"%s","readerType":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(event.conversationId()),
                RealtimeWebSocketHandler.escapeJson(event.readerType()));

        fanOut(event.businessId(), event.conversationType(), event.guestId(),
                "support.read", eventId, payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationState(SupportEvents.SupportConversationStateEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {"conversationId":"%s","status":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(event.conversationId()),
                RealtimeWebSocketHandler.escapeJson(event.status()));

        fanOut(event.businessId(), event.conversationType(), event.guestId(),
                "support.conversation", eventId, payload);
    }

    private void fanOut(String businessId, String conversationType, String guestId,
                        String type, String eventId, String payload) {
        Set<String> tenantSessions = Set.of();
        Set<String> adminSessions = Set.of();
        Set<String> guestSessions = Set.of();

        if (SupportConversation.TYPE_STOREFRONT.equals(conversationType)) {
            // Storefront buyers talk to the tenant's staff in their own dashboard.
            tenantSessions = sessionRegistry.findSessionsByBusinessChannel(businessId, CHANNEL);
            guestSessions = guestSessions(guestId);
        } else if (SupportConversation.TYPE_VISITOR.equals(conversationType)) {
            // kiosk.ke visitors talk to the platform team.
            adminSessions = sessionRegistry.findPlatformAdminSessions(CHANNEL);
            guestSessions = guestSessions(guestId);
        } else {
            // Classic tenant <-> super-admin thread.
            tenantSessions = sessionRegistry.findSessionsByBusinessChannel(businessId, CHANNEL);
            adminSessions = sessionRegistry.findPlatformAdminSessions(CHANNEL);
        }

        for (String sessionId : tenantSessions) {
            handler.sendFrame(sessionId, type, eventId, PRIORITY, null, payload);
        }
        for (String sessionId : adminSessions) {
            handler.sendFrame(sessionId, type, eventId, PRIORITY, null, payload);
        }
        for (String sessionId : guestSessions) {
            handler.sendFrame(sessionId, type, eventId, PRIORITY, null, payload);
        }
        if (!tenantSessions.isEmpty() || !adminSessions.isEmpty() || !guestSessions.isEmpty()) {
            log.debug("Support fan-out {}: business={} type={} tenantSessions={} adminSessions={} guestSessions={}",
                    type, businessId, conversationType,
                    tenantSessions.size(), adminSessions.size(), guestSessions.size());
        }
    }

    private Set<String> guestSessions(String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return Set.of();
        }
        return sessionRegistry.findSessionsByBusinessChannel(
                RealtimeScopes.GUEST, SupportService.GUEST_CHANNEL_PREFIX + guestId);
    }
}
