package zelisline.ub.messages.application;

import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;

/**
 * Strategy that turns a Talk to Us reply request into a persisted reply row.
 *
 * <p>Two implementations exist:
 * <ul>
 *   <li>{@link DirectContactReplySender} (cloud) — sends the reply through the
 *       shop's configured email / WhatsApp / SMS providers immediately.</li>
 *   <li>{@link DesktopQueuedContactReplySender} (desktop) — queues the reply
 *       locally ({@code outcome=queued}) so the till's sync can relay it to the
 *       shop's online instance, which performs the actual send.</li>
 * </ul>
 *
 * <p>The desktop SKU is offline-first: it deliberately holds no messaging
 * provider credentials (see {@code application-desktop.properties}), so the
 * cloud remains the single messaging config per shop.
 */
public interface ContactReplySender {

    /**
     * Validate the request against the message and persist the reply.
     *
     * @param replyId when non-blank, the persisted reply uses this id instead of
     *        a fresh UUID — used by the desktop → cloud relay so the cloud row
     *        correlates 1:1 with the till's queued row (idempotent re-pushes).
     */
    ContactMessageReply send(
            ContactMessage message,
            ContactMessageReplyRequest request,
            String actorUserId,
            String fromDisplayName,
            boolean platform,
            String replyId);
}
