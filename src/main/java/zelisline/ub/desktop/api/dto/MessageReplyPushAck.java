package zelisline.ub.desktop.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Per-reply acknowledgment for {@link MessageReplyPushRequest} — the till copies
 * the returned {@code outcome} / {@code detail} onto its local queued row and
 * stamps {@code cloud_synced_at}, so the thread converges with the cloud.
 */
public record MessageReplyPushAck(
        List<MessageReplyPushResult> results
) {

    public record MessageReplyPushResult(
        String replyId,
        String outcome,
        String detail,
        Instant createdAt
    ) {}
}
