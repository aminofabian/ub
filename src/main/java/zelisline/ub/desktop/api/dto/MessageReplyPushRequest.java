package zelisline.ub.desktop.api.dto;

import java.time.Instant;
import java.util.List;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import zelisline.ub.messages.domain.ContactReplyChannel;

/**
 * Batch of replies queued on a desktop till, relayed to the shop's online
 * instance (docs/scopes/DESKTOP_MESSAGES_SCOPE.md §7.2 — the "up" direction of
 * the message relay).
 *
 * <p>The till persists each reply locally with {@code outcome=queued} and
 * reuses that same {@code replyId} on the cloud, so a retried push after a
 * partial failure never double-sends: the cloud skips any replyId it already
 * has and returns the existing outcome.
 */
public record MessageReplyPushRequest(
        @Valid List<MessageReplyPushItem> replies
) {

    public record MessageReplyPushItem(
        @NotBlank String replyId,
        @NotBlank String contactMessageId,
        @NotNull ContactReplyChannel channel,
        @NotBlank String body,
        /** Till user who wrote the reply (display attribution on the cloud). */
        String sentByUserId,
        @NotNull Instant createdAt
    ) {}
}
