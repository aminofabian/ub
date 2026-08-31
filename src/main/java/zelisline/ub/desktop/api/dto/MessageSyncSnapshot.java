package zelisline.ub.desktop.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Cloud → till message pull (docs/scopes/DESKTOP_MESSAGES_SCOPE.md §7.4): the
 * shop's TENANT-scope Talk to Us messages that are <em>active</em> after the
 * till's cursor — created after it, or holding a reply created after it
 * (activity cursor, so a dashboard reply to an old message re-activates the
 * thread). Each item carries its full reply thread, so the till upserts a
 * message and all its replies in one shot — a reply can never arrive without
 * its parent.
 *
 * <p>Ordered by activity ascending; the till advances its
 * {@code lastMessagesPullAt} cursor to the newest activity timestamp seen, so
 * nothing is missed between pages.
 */
public record MessageSyncSnapshot(
        List<MessageSyncData> messages
) {

    public record MessageSyncData(
        String id,
        String name,
        String email,
        String phone,
        String body,
        String status,
        Instant readAt,
        String sourcePath,
        Instant createdAt,
        List<ReplySyncData> replies
    ) {}

    public record ReplySyncData(
        String id,
        String channel,
        String body,
        String outcome,
        String detail,
        String sentByUserId,
        Instant createdAt
    ) {}
}
