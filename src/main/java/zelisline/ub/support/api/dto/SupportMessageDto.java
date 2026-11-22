package zelisline.ub.support.api.dto;

import java.time.Instant;
import java.util.List;

public record SupportMessageDto(
        String id,
        String conversationId,
        String senderType,
        String senderUserId,
        String senderName,
        String body,
        String messageKind,
        SupportOrderCardDto orderCard,
        SupportWelcomeCardDto welcomeCard,
        SupportAttachmentDto attachment,
        SupportMessageReplyDto replyTo,
        Instant readAt,
        Instant createdAt,
        /** Emoji reaction summary; empty when none. Never null. */
        List<SupportReactionDto> reactions,
        /** Set when the body was edited after sending. */
        Instant editedAt,
        /** Set when the message was unsent; body/attachment are blanked. */
        Instant deletedAt,
        /** Set on a supplier's order appeal: the order it is about. Null otherwise. */
        SupportAppealDto appeal
) {}
