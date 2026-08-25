package zelisline.ub.support.application;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import zelisline.ub.platform.realtime.RealtimeScopes;
import zelisline.ub.platform.realtime.RealtimeWebSocketHandler;
import zelisline.ub.platform.realtime.SessionRegistry;
import zelisline.ub.platform.realtime.SupportTypingListener;
import zelisline.ub.support.domain.SupportConversation;

/**
 * Broadcasts typing presence for the support chat. Delivery mirrors
 * {@link SupportRealtimeBridge}: staff sockets on the {@code support} channel,
 * guest sockets on their own {@code support.guest:<guestId>} channel.
 */
@Service
public class SupportTypingService implements SupportTypingListener {

    private static final Logger log = LoggerFactory.getLogger(SupportTypingService.class);

    private static final String CHANNEL = "support";
    private static final String PRIORITY = "LOW";

    private final SessionRegistry sessionRegistry;
    private final RealtimeWebSocketHandler handler;
    private final SupportService supportService;

    public SupportTypingService(
            SessionRegistry sessionRegistry,
            RealtimeWebSocketHandler handler,
            SupportService supportService
    ) {
        this.sessionRegistry = sessionRegistry;
        this.handler = handler;
        this.supportService = supportService;
    }

    @Override
    public void onTyping(String userId, String businessId, String roleId, String conversationId, boolean typing) {
        String resolvedConversationId = conversationId;
        SupportConversation conversation = resolvedConversationId == null || resolvedConversationId.isBlank()
                ? supportService.findByBusinessId(businessId).orElse(null)
                : supportService.getConversationForTyping(resolvedConversationId);
        if (conversation == null) {
            return;
        }
        resolvedConversationId = conversation.getId();

        String type = conversation.getConversationType();
        boolean staff = SupportConversation.TYPE_STOREFRONT.equals(type)
                ? businessId.equals(conversation.getBusinessId()) // any tenant user staffs buyer chats
                : "SUPER_ADMIN".equals(roleId);

        String payload = """
                {"conversationId":"%s","typing":%b,"fromAdmin":%b,"userId":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(resolvedConversationId),
                typing,
                staff,
                RealtimeWebSocketHandler.escapeJson(userId));

        String eventId = UUID.randomUUID().toString();
        Set<String> adminSessions = Set.of();
        Set<String> tenantSessions = Set.of();
        Set<String> guestSessions = Set.of();

        boolean guestOwned = conversation.getGuestId() != null && !conversation.getGuestId().isBlank();
        if (SupportConversation.TYPE_VISITOR.equals(type)) {
            adminSessions = sessionRegistry.findPlatformAdminSessions(CHANNEL);
            guestSessions = guestSessions(conversation.getGuestId());
        } else if (SupportConversation.TYPE_STOREFRONT.equals(type)
                || (guestOwned && !SupportConversation.TYPE_TENANT.equals(type))) {
            tenantSessions = sessionRegistry.findSessionsByBusinessChannel(
                    conversation.getBusinessId(), CHANNEL);
            guestSessions = guestSessions(conversation.getGuestId());
        } else {
            adminSessions = sessionRegistry.findPlatformAdminSessions(CHANNEL);
            tenantSessions = sessionRegistry.findSessionsByBusinessChannel(
                    conversation.getBusinessId(), CHANNEL);
        }

        for (String sessionId : adminSessions) {
            handler.sendFrame(sessionId, "support.typing", eventId, PRIORITY, null, payload);
        }
        for (String sessionId : tenantSessions) {
            handler.sendFrame(sessionId, "support.typing", eventId, PRIORITY, null, payload);
        }
        for (String sessionId : guestSessions) {
            handler.sendFrame(sessionId, "support.typing", eventId, PRIORITY, null, payload);
        }
        log.debug("Support typing: staff={} conversation={} typing={}", staff, resolvedConversationId, typing);
    }

    private Set<String> guestSessions(String guestId) {
        if (guestId == null || guestId.isBlank()) {
            return Set.of();
        }
        return sessionRegistry.findSessionsByBusinessChannel(
                RealtimeScopes.GUEST, SupportService.GUEST_CHANNEL_PREFIX + guestId);
    }
}
