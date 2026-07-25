package zelisline.ub.messages.api.dto;

import java.time.Instant;

public record ContactMessageListItemResponse(
        String id,
        String name,
        String email,
        String phone,
        String preview,
        String status,
        Instant createdAt,
        Instant readAt
) {}
