package zelisline.ub.support.application;

import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import zelisline.ub.platform.realtime.RealtimeWebSocketHandler;
import zelisline.ub.platform.realtime.SessionRegistry;
import zelisline.ub.platform.realtime.SupportTypingListener;
import zelisline.ub.support.domain.SupportConversation;

/**
 * Broadcasts typing presence for the support chat. Tenant sessions broadcast to
 * the platform's admin sessions (and the rest of their own business so
 * multi-tab mirrors). Super-admin sessions broadcast to the tenant's business.
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
        boolean admin = "SUPER_ADMIN".equals(roleId);
        String resolvedConversationId = conversationId;
        String targetBusinessId = businessId;

        if (admin) {
            // The admin's session business scope is "platform"; resolve the real
            // tenant from the conversation they are typing in.
            if (conversationId == null || conversationId.isBlank()) {
                return;
            }
            SupportConversation conversation = supportService.getConversationForTyping(conversationId);
            if (conversation == null) {
                return;
            }
            targetBusinessId = conversation.getBusinessId();
            resolvedConversationId = conversation.getId();
        } else if (resolvedConversationId == null || resolvedConversationId.isBlank()) {
            // Tenant session: resolve their single conversation.
            SupportConversation conversation = supportService.findByBusinessId(businessId).orElse(null);
            if (conversation == null) {
                return;
            }
            resolvedConversationId = conversation.getId();
        }

        String payload = """
                {"conversationId":"%s","typing":%b,"fromAdmin":%b,"userId":"%s"}
                """.formatted(
                RealtimeWebSocketHandler.escapeJson(resolvedConversationId),
                typing,
                admin,
                RealtimeWebSocketHandler.escapeJson(userId));

        String eventId = UUID.randomUUID().toString();
        Set<String> adminSessions = sessionRegistry.findPlatformAdminSessions(CHANNEL);
        for (String sessionId : adminSessions) {
            handler.sendFrame(sessionId, "support.typing", eventId, PRIORITY, null, payload);
        }
        Set<String> tenantSessions = sessionRegistry.findSessionsByBusinessChannel(targetBusinessId, CHANNEL);
        for (String sessionId : tenantSessions) {
            handler.sendFrame(sessionId, "support.typing", eventId, PRIORITY, null, payload);
        }
        log.debug("Support typing: admin={} conversation={} typing={}", admin, resolvedConversationId, typing);
    }
}
