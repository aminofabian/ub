package zelisline.ub.messaging.application;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.crm.application.CrmDeliveryStatusService;
import zelisline.ub.crm.application.InboundEnvelope;
import zelisline.ub.crm.application.InboundIngest;
import zelisline.ub.crm.application.StatusEnvelope;
import zelisline.ub.messaging.domain.WhatsAppMessage;
import zelisline.ub.messaging.domain.WhatsAppMessageType;
import zelisline.ub.messaging.dto.MetaWebhookChange;
import zelisline.ub.messaging.dto.MetaWebhookContact;
import zelisline.ub.messaging.dto.MetaWebhookEntry;
import zelisline.ub.messaging.dto.MetaWebhookError;
import zelisline.ub.messaging.dto.MetaWebhookMessage;
import zelisline.ub.messaging.dto.MetaWebhookPayload;
import zelisline.ub.messaging.dto.MetaWebhookStatus;
import zelisline.ub.messaging.dto.MetaWebhookValue;

/**
 * Processes inbound WhatsApp webhook payloads from Meta Cloud API.
 * Parses the JSON, routes messages by type, and handles delivery status updates.
 *
 * <p>Never throws — all errors are caught and logged so the webhook controller
 * can always return 200 OK to Meta (otherwise Meta retries indefinitely).
 *
 * <p>Also hands inbound messages to the CRM inbox ({@link InboundIngest}) and applies
 * delivery-status callbacks ({@link CrmDeliveryStatusService}) behind the
 * {@code app.integrations.whatsapp.enabled} flag; both are swallowed so they can never
 * affect the webhook response to Meta.
 */
@Service
public class WhatsAppInboundProcessor {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppInboundProcessor.class);

    private final ObjectMapper objectMapper;
    private final WhatsAppMessageRouter messageRouter;
    private final InboundIngest inboundIngest;
    private final CrmDeliveryStatusService deliveryStatusService;

    public WhatsAppInboundProcessor(
            ObjectMapper objectMapper,
            WhatsAppMessageRouter messageRouter,
            InboundIngest inboundIngest,
            CrmDeliveryStatusService deliveryStatusService
    ) {
        this.objectMapper = objectMapper;
        this.messageRouter = messageRouter;
        this.inboundIngest = inboundIngest;
        this.deliveryStatusService = deliveryStatusService;
    }

    /**
     * Parses and processes a raw Meta webhook JSON payload.
     *
     * @param rawJson the raw POST body from Meta
     */
    public void process(String rawJson) {
        try {
            MetaWebhookPayload payload = objectMapper.readValue(rawJson, MetaWebhookPayload.class);
            if (payload == null || payload.entry() == null) {
                log.warn("WhatsApp inbound: parsed payload or entry list is null");
                return;
            }

            for (MetaWebhookEntry entry : payload.entry()) {
                if (entry.changes() == null) {
                    continue;
                }
                for (MetaWebhookChange change : entry.changes()) {
                    if (change.value() == null) {
                        continue;
                    }
                    processValue(change.value(), rawJson);
                }
            }
        } catch (Exception ex) {
            log.error("WhatsApp inbound: failed to process payload", ex);
        }
    }

    private void processValue(MetaWebhookValue value, String rawJson) {
        processMessages(value, rawJson);
        processStatuses(value);
        processErrors(value);
    }

    private void processMessages(MetaWebhookValue value, String rawJson) {
        if (value.messages() == null) {
            return;
        }

        String senderName = extractSenderName(value.contacts());

        for (MetaWebhookMessage message : value.messages()) {
            try {
                WhatsAppMessageType type = resolveType(message.type());
                String content = extractContent(message, type);

                WhatsAppMessage domainMessage = new WhatsAppMessage(
                    message.id(),
                    message.from(),
                    senderName,
                    type,
                    content,
                    rawJson,
                    Instant.now(),
                    value.metadata() != null ? value.metadata().phoneNumberId() : null
                );

                messageRouter.route(domainMessage);
                crmIngest(domainMessage, rawJson);
                logMessageDetails(message, type, senderName);
            } catch (Exception ex) {
                log.error("WhatsApp inbound: failed to process message id={}", message.id(), ex);
            }
        }
    }

    /**
     * Persist into the CRM inbox (M1). Gated inside {@link InboundIngest} by the feature
     * flag; any failure is swallowed so the webhook still answers Meta with 200.
     */
    private void crmIngest(WhatsAppMessage domainMessage, String rawJson) {
        try {
            InboundEnvelope envelope = new InboundEnvelope(
                    domainMessage.messageId(),
                    domainMessage.phoneNumberId(),
                    domainMessage.from(),
                    domainMessage.senderName(),
                    domainMessage.type() == null ? null : domainMessage.type().name().toLowerCase(),
                    domainMessage.content(),
                    domainMessage.receivedAt());
            inboundIngest.ingest(envelope, rawJson);
        } catch (Exception ex) {
            log.warn("WhatsApp inbound: CRM ingest failed (continuing) id={}: {}",
                    domainMessage.messageId(), ex.getMessage());
        }
    }

    private void processStatuses(MetaWebhookValue value) {
        if (value.statuses() == null) {
            return;
        }

        for (MetaWebhookStatus status : value.statuses()) {
            try {
                log.info("WhatsApp status: id={} status={} recipient={} timestamp={}",
                    status.id(), status.status(), status.recipientId(), status.timestamp());

                if (status.conversation() != null && status.conversation().origin() != null) {
                    log.debug("WhatsApp status conversation: id={} origin={}",
                        status.conversation().id(), status.conversation().origin().type());
                }

                crmStatus(status);
            } catch (Exception ex) {
                log.error("WhatsApp inbound: failed to process status id={}", status.id(), ex);
            }
        }
    }

    /**
     * Apply a delivery-status callback to the CRM (M1). Gated inside
     * {@link CrmDeliveryStatusService}; failures are swallowed.
     */
    private void crmStatus(MetaWebhookStatus status) {
        try {
            deliveryStatusService.applyStatus(
                    new StatusEnvelope(status.id(), status.status(), statusErrorDetail(status)));
        } catch (Exception ex) {
            log.warn("WhatsApp inbound: CRM status update failed (continuing) id={}: {}",
                    status.id(), ex.getMessage());
        }
    }

    /** Human-readable detail from Meta's {@code errors[]}, for {@code crm_message.failure_reason}. */
    private static String statusErrorDetail(MetaWebhookStatus status) {
        if (status.errors() == null || status.errors().isEmpty()) {
            return null;
        }
        MetaWebhookError error = status.errors().get(0);
        StringBuilder sb = new StringBuilder();
        if (error.title() != null && !error.title().isBlank()) {
            sb.append(error.title());
        }
        if (error.message() != null && !error.message().isBlank()) {
            if (sb.length() > 0) {
                sb.append(": ");
            }
            sb.append(error.message());
        }
        if (error.errorData() != null && error.errorData().details() != null
                && !error.errorData().details().isBlank()) {
            if (sb.length() > 0) {
                sb.append(" — ");
            }
            sb.append(error.errorData().details());
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void processErrors(MetaWebhookValue value) {
        if (value.errors() == null) {
            return;
        }

        for (MetaWebhookError error : value.errors()) {
            log.error("WhatsApp webhook error: code={} title={} message={}",
                error.code(), error.title(), error.message());
        }
    }

    private String extractSenderName(List<MetaWebhookContact> contacts) {
        if (contacts == null || contacts.isEmpty()) {
            return null;
        }
        MetaWebhookContact contact = contacts.get(0);
        if (contact.profile() != null) {
            return contact.profile().name();
        }
        return null;
    }

    private WhatsAppMessageType resolveType(String type) {
        if (type == null) {
            return WhatsAppMessageType.UNKNOWN;
        }
        return switch (type.toLowerCase()) {
            case "text" -> WhatsAppMessageType.TEXT;
            case "image" -> WhatsAppMessageType.IMAGE;
            case "document" -> WhatsAppMessageType.DOCUMENT;
            case "audio" -> WhatsAppMessageType.AUDIO;
            case "video" -> WhatsAppMessageType.VIDEO;
            case "location" -> WhatsAppMessageType.LOCATION;
            case "button" -> WhatsAppMessageType.BUTTON;
            case "interactive" -> WhatsAppMessageType.INTERACTIVE;
            case "order" -> WhatsAppMessageType.ORDER;
            case "sticker" -> WhatsAppMessageType.STICKER;
            case "reaction" -> WhatsAppMessageType.REACTION;
            case "system" -> WhatsAppMessageType.SYSTEM;
            default -> WhatsAppMessageType.UNKNOWN;
        };
    }

    private String extractContent(MetaWebhookMessage message, WhatsAppMessageType type) {
        return switch (type) {
            case TEXT -> message.text() != null ? message.text().body() : null;
            case IMAGE -> message.image() != null ? message.image().caption() : null;
            case DOCUMENT -> message.document() != null ? message.document().filename() : null;
            case LOCATION -> message.location() != null
                ? "lat=" + message.location().latitude() + ", lng=" + message.location().longitude()
                : null;
            case ORDER -> message.order() != null ? formatOrder(message.order()) : null;
            case BUTTON -> message.button() != null ? message.button().text() : null;
            case INTERACTIVE -> formatInteractive(message.interactive());
            case REACTION -> message.reaction() != null ? message.reaction().emoji() : null;
            default -> null;
        };
    }

    private String formatOrder(MetaWebhookMessage.Order order) {
        StringBuilder sb = new StringBuilder();
        sb.append("catalogId=").append(order.catalogId());
        if (order.text() != null) {
            sb.append(", text=").append(order.text());
        }
        if (order.productItems() != null && !order.productItems().isEmpty()) {
            sb.append(", items=[");
            for (MetaWebhookMessage.Order.ProductItem item : order.productItems()) {
                sb.append("{productRetailerId=").append(item.productRetailerId())
                  .append(", qty=").append(item.quantity())
                  .append(", price=").append(item.itemPrice())
                  .append(" " ).append(item.currency())
                  .append("}");
            }
            sb.append("]");
        }
        return sb.toString();
    }

    private String formatInteractive(MetaWebhookMessage.Interactive interactive) {
        if (interactive == null) {
            return null;
        }
        if (interactive.buttonReply() != null) {
            return "buttonReply: id=" + interactive.buttonReply().id() + ", title=" + interactive.buttonReply().title();
        }
        if (interactive.listReply() != null) {
            return "listReply: id=" + interactive.listReply().id() + ", title=" + interactive.listReply().title();
        }
        return null;
    }

    private void logMessageDetails(MetaWebhookMessage message, WhatsAppMessageType type, String senderName) {
        switch (type) {
            case TEXT -> log.info("WhatsApp text from={} name={}: {}",
                message.from(), senderName,
                message.text() != null ? message.text().body() : "(null)");
            case IMAGE -> log.info("WhatsApp image from={} name={} caption={} mediaId={}",
                message.from(), senderName,
                message.image() != null ? message.image().caption() : "(null)",
                message.image() != null ? message.image().id() : "(null)");
            case DOCUMENT -> log.info("WhatsApp document from={} name={} filename={} mimeType={}",
                message.from(), senderName,
                message.document() != null ? message.document().filename() : "(null)",
                message.document() != null ? message.document().mimeType() : "(null)");
            case LOCATION -> log.info("WhatsApp location from={} name={} lat={} lng={}",
                message.from(), senderName,
                message.location() != null ? message.location().latitude() : null,
                message.location() != null ? message.location().longitude() : null);
            case ORDER -> log.info("WhatsApp order from={} name={}: {}",
                message.from(), senderName, formatOrder(message.order()));
            case BUTTON -> log.info("WhatsApp button from={} name={} payload={} text={}",
                message.from(), senderName,
                message.button() != null ? message.button().payload() : null,
                message.button() != null ? message.button().text() : null);
            case INTERACTIVE -> log.info("WhatsApp interactive from={} name={}: {}",
                message.from(), senderName, formatInteractive(message.interactive()));
            case REACTION -> log.info("WhatsApp reaction from={} name={} emoji={} toMessageId={}",
                message.from(), senderName,
                message.reaction() != null ? message.reaction().emoji() : null,
                message.reaction() != null ? message.reaction().messageId() : null);
            default -> log.warn("WhatsApp unhandled type from={} name={}: {}",
                message.from(), senderName, message.type());
        }
    }
}
