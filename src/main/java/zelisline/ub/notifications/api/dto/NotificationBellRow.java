package zelisline.ub.notifications.api.dto;

import java.time.Instant;

/**
 * Lean bell row: exactly what the notification list paints, nothing else.
 *
 * <p>{@code payloadJson} is deliberately absent. The bell renders title + body +
 * destination, and the producers already put those in the payload as template
 * variables — shipping the whole payload for every row is what made the inbox
 * hundreds of kilobytes on a cold dashboard load.
 */
public record NotificationBellRow(
        String id,
        String type,
        String priority,
        String title,
        String body,
        String actionUrl,
        Instant readAt,
        Instant createdAt
) {
}
