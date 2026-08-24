package zelisline.ub.support.application;

import java.time.Instant;

/**
 * Domain events published by {@link SupportService} after successful writes.
 * {@link SupportRealtimeBridge} fans them out over the realtime WebSocket.
 */
public final class SupportEvents {

    private SupportEvents() {
    }

    public record SupportMessageSentEvent(
            String businessId,
            String conversationId,
            String messageId,
            String senderType,
            String senderUserId,
            String senderName,
            String body,
            Instant createdAt
    ) {}

    public record SupportMessagesReadEvent(
            String businessId,
            String conversationId,
            /** Which side just read: TENANT or SUPER_ADMIN. */
            String readerType
    ) {}

    public record SupportConversationStateEvent(
            String businessId,
            String conversationId,
            String status
    ) {}
}
