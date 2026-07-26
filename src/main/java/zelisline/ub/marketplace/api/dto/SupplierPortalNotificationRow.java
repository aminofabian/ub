package zelisline.ub.marketplace.api.dto;

import java.time.Instant;

public record SupplierPortalNotificationRow(
        String id,
        String type,
        String title,
        String body,
        String actionUrl,
        Instant createdAt,
        Instant readAt
) {
}
