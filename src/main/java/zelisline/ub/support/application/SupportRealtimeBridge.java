package zelisline.ub.support.application;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import zelisline.ub.platform.realtime.RealtimeWebSocketHandler;
import zelisline.ub.platform.realtime.SessionRegistry;

/**
 * Fans support-chat events out to the right WebSocket sessions after commit:
 * the tenant's business sessions (channel {@code support}) and the platform's
 * super-admin sessions (channel {@code support}).
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
                {"conversationId":"%s","messageId":"%s","senderType":"%s","senderUserId":"%s","senderName":"%s","body":"%s","createdAt":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(event.conversationId()),
                RealtimeWebSocketHandler.escapeJson(event.messageId()),
                RealtimeWebSocketHandler.escapeJson(event.senderType()),
                RealtimeWebSocketHandler.escapeJson(event.senderUserId()),
                RealtimeWebSocketHandler.escapeJson(event.senderName()),
                RealtimeWebSocketHandler.escapeJson(event.body()),
                event.createdAt().toString());

        fanOut(event.businessId(), "support.message", eventId, payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessagesRead(SupportEvents.SupportMessagesReadEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {"conversationId":"%s","readerType":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(event.conversationId()),
                RealtimeWebSocketHandler.escapeJson(event.readerType()));

        fanOut(event.businessId(), "support.read", eventId, payload);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConversationState(SupportEvents.SupportConversationStateEvent event) {
        String eventId = UUID.randomUUID().toString();
        String payload = """
                {"conversationId":"%s","status":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(event.conversationId()),
                RealtimeWebSocketHandler.escapeJson(event.status()));

        fanOut(event.businessId(), "support.conversation", eventId, payload);
    }

    private void fanOut(String businessId, String type, String eventId, String payload) {
        Set<String> tenantSessions = sessionRegistry.findSessionsByBusinessChannel(businessId, CHANNEL);
        for (String sessionId : tenantSessions) {
            handler.sendFrame(sessionId, type, eventId, PRIORITY, null, payload);
        }
        Set<String> adminSessions = sessionRegistry.findPlatformAdminSessions(CHANNEL);
        for (String sessionId : adminSessions) {
            handler.sendFrame(sessionId, type, eventId, PRIORITY, null, payload);
        }
        if (!tenantSessions.isEmpty() || !adminSessions.isEmpty()) {
            log.debug("Support fan-out {}: business={} tenantSessions={} adminSessions={}",
                    type, businessId, tenantSessions.size(), adminSessions.size());
        }
    }
}
