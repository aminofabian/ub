package zelisline.ub.support.api.dto;

import java.time.Instant;

public record SupportMessageDto(
        String id,
        String conversationId,
        String senderType,
        String senderUserId,
        String senderName,
        String body,
        Instant readAt,
        Instant createdAt
) {}
