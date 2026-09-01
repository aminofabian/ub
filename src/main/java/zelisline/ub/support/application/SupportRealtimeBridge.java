package zelisline.ub.support.application;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.platform.realtime.RealtimeScopes;
import zelisline.ub.platform.realtime.RealtimeWebSocketHandler;
import zelisline.ub.platform.realtime.SessionRegistry;
import zelisline.ub.support.api.dto.SupportAttachmentDto;
import zelisline.ub.support.api.dto.SupportMessageReplyDto;
import zelisline.ub.support.api.dto.SupportOrderCardDto;
import zelisline.ub.support.api.dto.SupportWelcomeCardDto;
import zelisline.ub.support.domain.SupportConversation;
import zelisline.ub.support.domain.SupportMessage;

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
 *       sockets on their guest channel. Never the super-admin inbox.</li>
 * </ul>
 * Guest-owned threads with a missing/blank type are treated like STOREFRONT so
 * they cannot accidentally fan out to platform admins.
 */
@Service
public class SupportRealtimeBridge {

    private static final Logger log = LoggerFactory.getLogger(SupportRealtimeBridge.class);

    static final String CHANNEL = "support";
    static final String PRIORITY = "HIGH";

    private final SessionRegistry sessionRegistry;
    private final RealtimeWebSocketHandler handler;
    private final ObjectMapper objectMapper;

    public SupportRealtimeBridge(
            SessionRegistry sessionRegistry,
            RealtimeWebSocketHandler handler,
            ObjectMapper objectMapper
    ) {
        this.sessionRegistry = sessionRegistry;
        this.handler = handler;
        this.objectMapper = objectMapper;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onMessageSent(SupportEvents.SupportMessageSentEvent event) {
        String eventId = UUID.randomUUID().toString();
        String attachmentJson = attachmentJson(event.attachment());
        String orderCardJson = toJsonOrNull(event.orderCard());
        String welcomeCardJson = toJsonOrNull(event.welcomeCard());
        String kind = event.messageKind() == null || event.messageKind().isBlank()
                ? SupportMessage.KIND_TEXT
                : event.messageKind();
        String replyToJson = toJsonOrNull(event.replyTo());
        String payload = """
                {"conversationId":"%s","messageId":"%s","senderType":"%s","senderUserId":"%s","senderName":"%s","body":"%s","messageKind":"%s","orderCard":%s,"welcomeCard":%s,"attachment":%s,"replyTo":%s,"createdAt":"%s","conversationType":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(event.conversationId()),
                RealtimeWebSocketHandler.escapeJson(event.messageId()),
                RealtimeWebSocketHandler.escapeJson(event.senderType()),
                RealtimeWebSocketHandler.escapeJson(event.senderUserId()),
                RealtimeWebSocketHandler.escapeJson(event.senderName()),
                RealtimeWebSocketHandler.escapeJson(event.body()),
                RealtimeWebSocketHandler.escapeJson(kind),
                orderCardJson,
                welcomeCardJson,
                attachmentJson,
                replyToJson,
                event.createdAt().toString(),
                RealtimeWebSocketHandler.escapeJson(event.conversationType()));

        fanOut(event.businessId(), event.conversationType(), event.guestId(),
                "support.message", eventId, payload);
    }

    private String toJsonOrNull(SupportMessageReplyDto replyTo) {
        return toJsonOrNull((Object) replyTo);
    }

    private String toJsonOrNull(Object value) {
        if (value == null) {
            return "null";
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize support realtime payload: {}", e.toString());
            return "null";
        }
    }

    private String toJsonOrNull(SupportOrderCardDto orderCard) {
        return toJsonOrNull((Object) orderCard);
    }

    private String toJsonOrNull(SupportWelcomeCardDto welcomeCard) {
        return toJsonOrNull((Object) welcomeCard);
    }

    private static String attachmentJson(SupportAttachmentDto attachment) {
        if (attachment == null || attachment.url() == null || attachment.url().isBlank()) {
            return "null";
        }
        return """
                {"url":"%s","publicId":"%s","fileName":"%s","contentType":"%s","bytes":%s}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(nullToEmpty(attachment.url())),
                RealtimeWebSocketHandler.escapeJson(nullToEmpty(attachment.publicId())),
                RealtimeWebSocketHandler.escapeJson(nullToEmpty(attachment.fileName())),
                RealtimeWebSocketHandler.escapeJson(nullToEmpty(attachment.contentType())),
                attachment.bytes() == null ? "null" : attachment.bytes().toString()
        ).trim();
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
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

        String normalizedType = conversationType == null ? "" : conversationType.trim();
        boolean guestOwned = guestId != null && !guestId.isBlank();

        if (SupportConversation.TYPE_VISITOR.equals(normalizedType)) {
            // kiosk.ke visitors talk to the platform team.
            adminSessions = sessionRegistry.findPlatformAdminSessions(CHANNEL);
            guestSessions = guestSessions(guestId);
        } else if (SupportConversation.TYPE_STOREFRONT.equals(normalizedType)
                || (guestOwned && !SupportConversation.TYPE_TENANT.equals(normalizedType))) {
            // Storefront buyers (and any other guest-owned thread that is not an
            // explicit VISITOR) talk to the tenant's staff — never the platform inbox.
            // The guestOwned fallback stops a missing/blank conversationType from
            // falling through into the classic tenant↔admin fan-out.
            tenantSessions = sessionRegistry.findSessionsByBusinessChannel(businessId, CHANNEL);
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
                    type, businessId, normalizedType,
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
