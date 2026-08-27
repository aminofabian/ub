package zelisline.ub.support.application;

import java.time.Instant;

import zelisline.ub.support.api.dto.SupportAttachmentDto;
import zelisline.ub.support.api.dto.SupportOrderCardDto;
import zelisline.ub.support.api.dto.SupportWelcomeCardDto;

/**
 * Domain events published by {@link SupportService} after successful writes.
 * {@link SupportRealtimeBridge} fans them out over the realtime WebSocket.
 *
 * <p>{@code conversationType} ({@code TENANT|VISITOR|STOREFRONT}) and
 * {@code guestId} let the bridge target the right sessions: staff sockets on
 * the {@code support} channel, guest sockets on {@code support.guest:<guestId>}.
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
            String messageKind,
            SupportOrderCardDto orderCard,
            SupportWelcomeCardDto welcomeCard,
            SupportAttachmentDto attachment,
            Instant createdAt,
            String conversationType,
            String guestId
    ) {}

    public record SupportMessagesReadEvent(
            String businessId,
            String conversationId,
            /** Which side just read: TENANT, SUPER_ADMIN, or GUEST. */
            String readerType,
            String conversationType,
            String guestId
    ) {}

    public record SupportConversationStateEvent(
            String businessId,
            String conversationId,
            String status,
            String conversationType,
            String guestId
    ) {}
}
