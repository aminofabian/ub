package zelisline.ub.support.api.dto;

import java.time.Instant;

public record SupportConversationDto(
        String id,
        String businessId,
        String businessName,
        String businessSlug,
        String conversationType,
        String guestId,
        String guestName,
        String status,
        String subject,
        String createdByName,
        Instant lastMessageAt,
        String lastMessagePreview,
        Instant tenantLastReadAt,
        Instant adminLastReadAt,
        long unreadCount,
        Instant createdAt,
        Instant updatedAt
) {}
