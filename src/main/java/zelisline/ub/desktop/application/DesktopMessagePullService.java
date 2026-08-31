package zelisline.ub.desktop.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import zelisline.ub.desktop.api.dto.MessageSyncSnapshot;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.domain.ContactMessageStatus;
import zelisline.ub.messages.domain.ContactReplyChannel;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;
import zelisline.ub.messages.repository.ContactMessageRepository;

/**
 * Desktop-side pull of the shop's Talk to Us inbox from its online instance —
 * the "down" direction of the message relay
 * (docs/scopes/DESKTOP_MESSAGES_SCOPE.md §7.4).
 *
 * <p>The cloud returns messages <em>active</em> after the till's cursor
 * (created after it, or holding a reply created after it), ordered by activity
 * ascending and each carrying its full reply thread. The till upserts each
 * message and its replies in one shot — a reply can never arrive without its
 * parent — and advances the {@code lastMessagesPullAt} cursor (in
 * {@code cloud-sync.json}) to the newest activity timestamp seen, so nothing is
 * missed between pages.
 *
 * <p>Read receipts are one-way cloud → till in v1: the till adopts the cloud's
 * READ state but never downgrades a message it has already marked read locally
 * (till-side reads do not propagate up yet). Local reply rows are never
 * clobbered — a {@code queued} reply converges through the push ack, and any
 * reply the till already has is skipped.
 */
@Service
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopMessagePullService {

    private static final Logger log = LoggerFactory.getLogger(DesktopMessagePullService.class);

    /** Page size for the pull (matches the cloud controller's cap). */
    private static final int PAGE_SIZE = 100;

    private final ContactMessageRepository messageRepository;
    private final ContactMessageReplyRepository replyRepository;
    private final CloudSyncSession cloudSyncSession;
    private final TransactionTemplate transactionTemplate;
    private final RestClient.Builder restClientBuilder;

    @Value("${app.desktop.business-id:}")
    private String desktopBusinessId;

    public record MessagePullResult(int messages, int replies) {}

    public MessagePullResult pullMessages() {
        String localId = desktopBusinessId == null ? "" : desktopBusinessId.trim();
        CloudSyncSession.Session mapping = cloudSyncSession.load().orElse(null);
        if (mapping == null || localId.isEmpty()) {
            throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "This PC is not connected to an online shop yet"
            );
        }

        RestClient client = restClientBuilder.baseUrl(mapping.origin()).build();
        Instant cursor = mapping.lastMessagesPullAt() != null
            ? mapping.lastMessagesPullAt()
            : Instant.EPOCH;

        int messages = 0;
        int replies = 0;
        while (true) {
            MessageSyncSnapshot snapshot = fetchMessages(client, mapping, cursor);
            List<MessageSyncSnapshot.MessageSyncData> batch = snapshot.messages();
            if (batch == null || batch.isEmpty()) {
                break;
            }
            MessageUpsert upsert = transactionTemplate.execute(status ->
                upsertMessages(localId, batch));
            if (upsert == null) {
                throw new ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "Message pull failed — nothing was written"
                );
            }
            messages += upsert.messages();
            replies += upsert.replies();

            Instant newest = batch.stream()
                .map(DesktopMessagePullService::activityOf)
                .filter(Objects::nonNull)
                .max(Instant::compareTo)
                .orElse(null);
            if (newest != null && newest.isAfter(cursor)) {
                cursor = newest;
                cloudSyncSession.persistLastMessagesPullAt(mapping, cursor);
            }
            if (batch.size() < PAGE_SIZE) {
                break;
            }
        }
        if (messages > 0 || replies > 0) {
            log.info(
                "[DesktopSync] message pull: {} message(s), {} reply(ies) from {} (cursor now {})",
                messages, replies, mapping.origin(), cursor);
        }
        return new MessagePullResult(messages, replies);
    }

    /** A message's activity = newest of its own creation and any reply's creation. */
    private static Instant activityOf(MessageSyncSnapshot.MessageSyncData d) {
        Instant newest = d.createdAt();
        if (d.replies() != null) {
            for (MessageSyncSnapshot.ReplySyncData r : d.replies()) {
                if (r.createdAt() != null && (newest == null || r.createdAt().isAfter(newest))) {
                    newest = r.createdAt();
                }
            }
        }
        return newest;
    }

    private record MessageUpsert(int messages, int replies) {}

    private MessageUpsert upsertMessages(
            String localId, List<MessageSyncSnapshot.MessageSyncData> batch) {
        int messages = 0;
        int replies = 0;
        for (MessageSyncSnapshot.MessageSyncData d : batch) {
            ContactMessage existing = messageRepository.findById(d.id()).orElse(null);
            if (existing == null) {
                ContactMessage m = new ContactMessage();
                m.setId(d.id());
                m.setScope(ContactMessageScope.TENANT);
                m.setBusinessId(localId);
                m.setName(d.name());
                m.setEmail(d.email());
                m.setPhone(d.phone());
                m.setBody(d.body());
                m.setStatus(parseStatus(d.status()));
                m.setReadAt(d.readAt());
                m.setSourcePath(d.sourcePath());
                m.setCreatedAt(d.createdAt());
                messageRepository.save(m);
                messages++;
            } else {
                boolean changed = false;
                if (!Objects.equals(existing.getName(), d.name())) {
                    existing.setName(d.name());
                    changed = true;
                }
                if (!Objects.equals(existing.getEmail(), d.email())) {
                    existing.setEmail(d.email());
                    changed = true;
                }
                if (!Objects.equals(existing.getPhone(), d.phone())) {
                    existing.setPhone(d.phone());
                    changed = true;
                }
                if (!Objects.equals(existing.getBody(), d.body())) {
                    existing.setBody(d.body());
                    changed = true;
                }
                if (!Objects.equals(existing.getSourcePath(), d.sourcePath())) {
                    existing.setSourcePath(d.sourcePath());
                    changed = true;
                }
                // Read receipts are one-way cloud → till: adopt the cloud's READ
                // state, but never downgrade a message read on the till.
                ContactMessageStatus cloudStatus = parseStatus(d.status());
                if (existing.getStatus() != ContactMessageStatus.READ
                        && cloudStatus == ContactMessageStatus.READ) {
                    existing.setStatus(ContactMessageStatus.READ);
                    existing.setReadAt(d.readAt());
                    changed = true;
                }
                if (changed) {
                    messageRepository.save(existing);
                    messages++;
                }
            }

            // Replies ride with their parent — no orphan risk. Never clobber a
            // reply the till already has: a locally queued reply converges via
            // the push ack; an already-synced one is a no-op.
            if (d.replies() != null) {
                for (MessageSyncSnapshot.ReplySyncData r : d.replies()) {
                    if (replyRepository.existsById(r.id())) {
                        continue;
                    }
                    ContactMessageReply row = new ContactMessageReply();
                    row.setId(r.id());
                    row.setContactMessageId(d.id());
                    row.setChannel(parseChannel(r.channel()));
                    row.setBody(r.body());
                    row.setOutcome(r.outcome());
                    row.setDetail(r.detail());
                    row.setSentByUserId(r.sentByUserId());
                    row.setCreatedAt(r.createdAt());
                    replyRepository.save(row);
                    replies++;
                }
            }
        }
        return new MessageUpsert(messages, replies);
    }

    private MessageSyncSnapshot fetchMessages(
            RestClient client, CloudSyncSession.Session mapping, Instant since) {
        try {
            return doFetchMessages(client, mapping, since);
        } catch (Exception e) {
            if (!isUnauthorized(e)) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not download messages (" + e.getMessage() + ")"
                );
            }
            CloudSyncSession.Session refreshed = cloudSyncSession
                .refresh(client, mapping)
                .orElse(null);
            if (refreshed == null) {
                throw new ResponseStatusException(
                    HttpStatus.UNAUTHORIZED,
                    "Your online-shop session has expired — open Settings → Sync to reconnect"
                );
            }
            try {
                return doFetchMessages(client, refreshed, since);
            } catch (Exception e2) {
                throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY,
                    "Could not download messages (" + e2.getMessage() + ")"
                );
            }
        }
    }

    private MessageSyncSnapshot doFetchMessages(
            RestClient client, CloudSyncSession.Session session, Instant since) {
        MessageSyncSnapshot snapshot = client
            .get()
            .uri("/api/v1/desktop/sync/messages?since=" + since)
            .header("Authorization", "Bearer " + session.accessToken())
            .header("X-Tenant-Id", session.cloudBusinessId())
            .retrieve()
            .body(MessageSyncSnapshot.class);
        if (snapshot == null) {
            throw new ResponseStatusException(
                HttpStatus.BAD_GATEWAY,
                "The online shop returned an empty messages snapshot"
            );
        }
        return snapshot;
    }

    private static boolean isUnauthorized(Exception e) {
        return e.getMessage() != null
            && (e.getMessage().contains("401") || e.getMessage().contains("Unauthorized"));
    }

    private static ContactMessageStatus parseStatus(String status) {
        try {
            return status == null ? ContactMessageStatus.UNREAD : ContactMessageStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            return ContactMessageStatus.UNREAD;
        }
    }

    private static ContactReplyChannel parseChannel(String channel) {
        return channel == null ? null : ContactReplyChannel.valueOf(channel);
    }
}
