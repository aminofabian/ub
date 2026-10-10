package zelisline.ub.crm.realtime;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import zelisline.ub.crm.events.CrmConversationUpdatedEvent;
import zelisline.ub.crm.events.CrmMessageCreatedEvent;
import zelisline.ub.crm.events.CrmMessageStatusEvent;
import zelisline.ub.platform.realtime.RealtimeWebSocketHandler;
import zelisline.ub.platform.realtime.SessionRegistry;

/**
 * Fans out CRM frames to a shop's sessions after commit (mirrors {@code platform.realtime.RealtimeBridge}).
 * Lives in {@code crm} and depends on {@code platform.realtime} so the direction stays
 * {@code crm -> platform}.
 *
 * <p>Frames: {@code crm.message.created}, {@code crm.conversation.updated}, {@code crm.message.status}.
 * See {@code docs/scopes/whatsapp-crm/SCOPE.md} §8.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CrmRealtimeBridge {

    private final SessionRegistry sessionRegistry;
    private final RealtimeWebSocketHandler handler;
    private final ObjectMapper objectMapper;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageCreated(CrmMessageCreatedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", nz(event.conversationId()));
        data.put("messageId", nz(event.messageId()));
        data.put("direction", nz(event.direction()));
        fanOut(event.businessId(), "crm.message.created", data, "MEDIUM");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onConversationUpdated(CrmConversationUpdatedEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", nz(event.conversationId()));
        data.put("status", nz(event.status()));
        data.put("assignedUserId", nz(event.assignedUserId()));
        data.put("unreadCount", event.unreadCount());
        fanOut(event.businessId(), "crm.conversation.updated", data, "LOW");
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onMessageStatus(CrmMessageStatusEvent event) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("conversationId", nz(event.conversationId()));
        data.put("waMessageId", nz(event.waMessageId()));
        data.put("status", nz(event.status()));
        fanOut(event.businessId(), "crm.message.status", data, "LOW");
    }

    private void fanOut(String businessId, String type, Map<String, Object> data, String priority) {
        if (businessId == null || businessId.isBlank()) {
            return;
        }
        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(data);
        } catch (Exception ex) {
            log.warn("crm realtime: serialization failed type={}", type);
            return;
        }
        String eventId = UUID.randomUUID().toString();
        Set<String> sessionIds = sessionRegistry.findAllSessionsForBusiness(businessId);
        for (String sid : sessionIds) {
            handler.sendFrame(sid, type, eventId, priority, Instant.now(), payloadJson);
        }
        log.debug("crm realtime {} business={} sessions={}", type, businessId, sessionIds.size());
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
