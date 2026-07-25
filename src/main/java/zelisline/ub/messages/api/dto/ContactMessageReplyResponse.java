package zelisline.ub.messages.api.dto;

import java.time.Instant;

public record ContactMessageReplyResponse(
        String id,
        String channel,
        String body,
        String outcome,
        String detail,
        String sentByUserId,
        Instant createdAt
) {}
