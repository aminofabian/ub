package zelisline.ub.support.api.dto;

import java.time.Instant;

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
        Instant createdAt
) {}
