package zelisline.ub.desktop.application;

import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.desktop.api.dto.MessageReplyPushAck;
import zelisline.ub.desktop.api.dto.MessageReplyPushRequest;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;

/**
 * Desktop-side push of queued Talk to Us replies to the shop's online instance
 * — the "up" direction of the message relay (docs/scopes/DESKTOP_MESSAGES_SCOPE.md).
 *
 * <p>The till has no messaging providers of its own; {@code DesktopQueuedContactReplySender}
 * persists replies with {@code outcome=queued}, and this service flushes them to
 * the cloud, which sends them through the shop's configured providers and
 * acknowledges each one. The ack's {@code outcome}/{@code detail} overwrite the
 * local queued row and {@code cloud_synced_at} is stamped, so a reply never
 * re-sends after it has been acknowledged.
 *
 * <p>Sync is store-and-forward like sales: an unreachable online shop leaves the
 * rows pending and the next run (startup + periodic retry, see
 * {@link DesktopSyncScheduler}) flushes them. The cloud ingest is idempotent by
 * reply id, so a retried push after a partial failure is safe.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopMessagePushService {

    private static final Logger log = LoggerFactory.getLogger(DesktopMessagePushService.class);

    /** Max replies per push batch (matches the till-side batch size). */
    private static final int REPLY_BATCH_SIZE = 50;

    private final ContactMessageReplyRepository replyRepository;
    private final CloudSyncSession cloudSyncSession;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    public record MessagePushResult(int repliesPushed, boolean configured) {}

    public MessagePushResult pushPendingReplies() {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        CloudSyncSession.Session mapping = cloudSyncSession
            .load()
            .orElse(null);
        if (mapping == null || localId.isEmpty()) {
            log.info("[DesktopSync] no cloud mapping — nothing to push.");
            return new MessagePushResult(0, false);
        }

        List<ContactMessageReply> pending = replyRepository.findQueuedForDesktopSync(
            localId, PageRequest.of(0, REPLY_BATCH_SIZE));
        if (pending.isEmpty()) {
            return new MessagePushResult(0, true);
        }
        log.info(
            "[DesktopSync] pushing {} queued message reply(ies) to {}",
            pending.size(), mapping.origin());

        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();
        MessageReplyPushAck ack = postBatch(client, mapping, buildBatch(pending));

        Instant syncedAt = Instant.now();
        int applied = 0;
        for (MessageReplyPushAck.MessageReplyPushResult result : ack.results()) {
            ContactMessageReply reply = replyRepository
                .findById(result.replyId())
                .orElse(null);
            if (reply == null) {
                continue;
            }
            reply.setOutcome(result.outcome());
            reply.setDetail(result.detail());
            reply.setCloudSyncedAt(syncedAt);
            replyRepository.save(reply);
            applied++;
        }
        log.info(
            "[DesktopSync] message replies: acknowledged {} of {} queued reply(ies)",
            applied, pending.size());
        return new MessagePushResult(applied, true);
    }

    private MessageReplyPushRequest buildBatch(List<ContactMessageReply> pending) {
        List<MessageReplyPushRequest.MessageReplyPushItem> items = pending.stream()
            .map(r -> new MessageReplyPushRequest.MessageReplyPushItem(
                r.getId(),
                r.getContactMessageId(),
                r.getChannel(),
                r.getBody(),
                r.getSentByUserId(),
                r.getCreatedAt()))
            .toList();
        return new MessageReplyPushRequest(items);
    }

    /**
     * POST the batch to the cloud; refreshes the stored token once and retries
     * when the access token has expired (same contract as the sales push).
     */
    private MessageReplyPushAck postBatch(
            RestClient client,
            CloudSyncSession.Session session,
            MessageReplyPushRequest batch) {
        try {
            return doPost(client, session, batch);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not upload message replies to the online shop (" + e.getMessage() + ")"
                );
            }
            CloudSyncSession.Session refreshed = cloudSyncSession
                .refresh(client, session)
                .orElse(null);
            if (refreshed == null) {
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Your online-shop session has expired — open Settings → Sync to reconnect"
                );
            }
            try {
                return doPost(client, refreshed, batch);
            } catch (Exception e2) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not upload message replies to the online shop (" + e2.getMessage() + ")"
                );
            }
        }
    }

    private MessageReplyPushAck doPost(
            RestClient client,
            CloudSyncSession.Session session,
            MessageReplyPushRequest batch) {
        MessageReplyPushAck ack = client
            .post()
            .uri("/api/v1/desktop/sync/message-replies")
            .header("Authorization", "Bearer " + session.accessToken())
            .header("X-Tenant-Id", session.cloudBusinessId())
            .contentType(MediaType.APPLICATION_JSON)
            .body(batch)
            .retrieve()
            .body(MessageReplyPushAck.class);
        if (ack == null || ack.results() == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty message-reply acknowledgment"
            );
        }
        return ack;
    }

    private static boolean isUnauthorized(Exception e) {
        return e.getMessage() != null
            && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"));
    }
}
