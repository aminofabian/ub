package zelisline.ub.messages.application;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.domain.ContactReplyChannel;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;

/**
 * Desktop implementation of {@link ContactReplySender}: persists the reply with
 * {@code outcome=queued} instead of sending it.
 *
 * <p>The desktop SKU is offline-first and holds no messaging provider
 * credentials (see {@code application-desktop.properties}), so the actual send
 * happens on the shop's online instance: {@link DesktopMessagePushService}
 * flushes queued replies to the cloud, which sends them through the shop's
 * configured providers and reports the real outcome back (which overwrites the
 * local {@code queued} row — see {@code docs/scopes/DESKTOP_MESSAGES_SCOPE.md}).
 *
 * <p>Contact validation is kept identical to {@link DirectContactReplySender} so
 * the till surfaces the same 400s before anything is queued. Provider-config
 * checks are intentionally skipped — those belong to the cloud, which decides
 * whether the send can go out.
 */
@Component
@Profile("desktop")
@RequiredArgsConstructor
public class DesktopQueuedContactReplySender implements ContactReplySender {

    private final ContactMessageReplyRepository contactMessageReplyRepository;

    @Override
    public ContactMessageReply send(
            ContactMessage message,
            ContactMessageReplyRequest request,
            String actorUserId,
            String fromDisplayName,
            boolean platform,
            String replyId
    ) {
        String body = request.body().trim();
        ContactReplyChannel channel = request.channel();
        switch (channel) {
            case EMAIL -> {
                if (message.getEmail() == null || message.getEmail().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message has no email address");
                }
            }
            case WHATSAPP, SMS -> requirePhone(message);
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported channel");
        }

        ContactMessageReply reply = new ContactMessageReply();
        if (replyId != null && !replyId.isBlank()) {
            reply.setId(replyId);
        }
        reply.setContactMessageId(message.getId());
        reply.setChannel(channel);
        reply.setBody(body);
        reply.setOutcome("queued");
        reply.setDetail("Will send when the till is online");
        reply.setSentByUserId(actorUserId);
        return contactMessageReplyRepository.save(reply);
    }

    private static void requirePhone(ContactMessage message) {
        if (message.getPhone() == null || message.getPhone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message has no phone number");
        }
    }
}
