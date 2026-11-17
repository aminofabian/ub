package zelisline.ub.support.application;

import java.time.Instant;

import java.util.List;

import zelisline.ub.support.api.dto.SupportAttachmentDto;
import zelisline.ub.support.api.dto.SupportMessageReplyDto;
import zelisline.ub.support.api.dto.SupportOrderCardDto;
import zelisline.ub.support.api.dto.SupportReactionDto;
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
            SupportMessageReplyDto replyTo,
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

    /**
     * An emoji reaction was added or removed on a message. {@code summary} is the
     * message's full per-emoji count after the change (each entry's {@code mine}
     * is left false here — the client recomputes it against its own identity).
     */
    public record SupportReactionEvent(
            String businessId,
            String conversationId,
            String messageId,
            String emoji,
            String actorType,
            String actorRef,
            /** ADD or REMOVE. */
            String action,
            List<SupportReactionDto> summary,
            String conversationType,
            String guestId
    ) {}

    public record SupportMessageEditedEvent(
            String businessId,
            String conversationId,
            String messageId,
            String body,
            Instant editedAt,
            String conversationType,
            String guestId
    ) {}

    public record SupportMessageDeletedEvent(
            String businessId,
            String conversationId,
            String messageId,
            Instant deletedAt,
            String conversationType,
            String guestId
    ) {}
}
