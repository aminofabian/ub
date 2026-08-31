package zelisline.ub.messages.application;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.domain.ContactReplyChannel;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;
import zelisline.ub.messaging.application.CustomerMessageDispatcher;
import zelisline.ub.messaging.application.TenantMessagingConfig;

/**
 * Cloud implementation of {@link ContactReplySender}: sends the reply through
 * the shop's configured email / WhatsApp / SMS providers immediately.
 *
 * <p>Not active on the desktop profile — a till holds no messaging provider
 * credentials and uses {@link DesktopQueuedContactReplySender} instead.
 */
@Component
@Profile("!desktop")
@RequiredArgsConstructor
public class DirectContactReplySender implements ContactReplySender {

    private final ContactMessageReplyRepository contactMessageReplyRepository;
    private final NotificationService notificationService;
    private final CustomerMessageDispatcher customerMessageDispatcher;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;

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
        String outcome;
        String detail;

        switch (channel) {
            case EMAIL -> {
                if (message.getEmail() == null || message.getEmail().isBlank()) {
                    throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message has no email address");
                }
                String subject = "Re: your message to " + fromDisplayName;
                notificationService.sendContactReplyEmail(
                        message.getEmail(), subject, body, fromDisplayName);
                outcome = "sent";
                detail = "email";
            }
            case WHATSAPP -> {
                String phone = requirePhone(message);
                TenantMessagingConfig messaging = resolveMessaging(message, platform);
                if (!messaging.metaWhatsAppConfigured() && !messaging.smsConfigured()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "WhatsApp or SMS is not configured for this inbox");
                }
                // Prefer WhatsApp; fall back to SMS automatically when Meta rejects the send.
                CustomerMessageDispatcher.DeliveryResult result =
                        customerMessageDispatcher.deliverDirect(messaging, phone, body);
                outcome = result.outcome();
                detail = truncate(result.channel() + ":" + result.detail());
                if (!"sent".equals(outcome) && !"stub".equals(outcome)) {
                    persistFailed(message, channel, body, actorUserId, outcome, detail, replyId);
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "WhatsApp/SMS send failed: " + result.detail());
                }
            }
            case SMS -> {
                String phone = requirePhone(message);
                TenantMessagingConfig messaging = resolveMessaging(message, platform);
                if (!messaging.smsConfigured()) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST, "SMS is not configured for this inbox");
                }
                CustomerMessageDispatcher.DeliveryResult result =
                        customerMessageDispatcher.deliverSmsOnly(messaging, phone, body);
                outcome = result.outcome();
                detail = truncate(result.channel() + ":" + result.detail());
                if (!"sent".equals(outcome) && !"stub".equals(outcome)) {
                    persistFailed(message, channel, body, actorUserId, outcome, detail, replyId);
                    throw new ResponseStatusException(
                            HttpStatus.BAD_GATEWAY, "SMS send failed: " + result.detail());
                }
            }
            default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported channel");
        }

        ContactMessageReply reply = new ContactMessageReply();
        if (replyId != null && !replyId.isBlank()) {
            reply.setId(replyId);
        }
        reply.setContactMessageId(message.getId());
        reply.setChannel(channel);
        reply.setBody(body);
        reply.setOutcome(outcome);
        reply.setDetail(detail);
        reply.setSentByUserId(actorUserId);
        return contactMessageReplyRepository.save(reply);
    }

    private TenantMessagingConfig resolveMessaging(ContactMessage message, boolean platform) {
        if (platform) {
            return messagingSettingsService.resolvePlatformForContactReply();
        }
        return messagingSettingsService.resolveForTest(message.getBusinessId());
    }

    private void persistFailed(
            ContactMessage message,
            ContactReplyChannel channel,
            String body,
            String actorUserId,
            String outcome,
            String detail,
            String replyId
    ) {
        ContactMessageReply reply = new ContactMessageReply();
        if (replyId != null && !replyId.isBlank()) {
            reply.setId(replyId);
        }
        reply.setContactMessageId(message.getId());
        reply.setChannel(channel);
        reply.setBody(body);
        reply.setOutcome(outcome);
        reply.setDetail(detail);
        reply.setSentByUserId(actorUserId);
        contactMessageReplyRepository.save(reply);
    }

    private static String requirePhone(ContactMessage message) {
        if (message.getPhone() == null || message.getPhone().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message has no phone number");
        }
        return message.getPhone();
    }

    private static String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() <= 1000 ? value : value.substring(0, 1000);
    }
}
