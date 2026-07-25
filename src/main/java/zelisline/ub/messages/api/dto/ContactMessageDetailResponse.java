package zelisline.ub.messages.api.dto;

import java.time.Instant;
import java.util.List;

public record ContactMessageDetailResponse(
        String id,
        String name,
        String email,
        String phone,
        String body,
        String status,
        Instant createdAt,
        Instant readAt,
        String sourcePath,
        List<ContactMessageReplyResponse> replies
) {}
