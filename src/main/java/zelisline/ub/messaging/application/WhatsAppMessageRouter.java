package zelisline.ub.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.messaging.domain.WhatsAppMessage;

/**
 * Routes inbound WhatsApp messages to the appropriate handler.
 *
 * <ul>
 *   <li>{@code TEXT} → merchant reply commands first ({@code CONFIRM/READY/COMPLETE <code>}
 *       drive fulfillment status, scope §19); anything else falls through to logging.</li>
 *   <li>{@code ORDER} → Meta catalog orders (future: create a draft sale/cart).</li>
 *   <li>{@code BUTTON}/{@code INTERACTIVE} → menu selections (future).</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
public class WhatsAppMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessageRouter.class);

    private final WhatsAppOrderReplyService orderReplyService;

    /**
     * Routes a parsed WhatsApp message based on its type.
     *
     * @param message the parsed domain message
     */
    public void route(WhatsAppMessage message) {
        if (message == null) {
            log.warn("WhatsApp router: received null message");
            return;
        }

        log.info("WhatsApp routing: type={} from={} name={} messageId={}",
            message.type(), message.from(), message.senderName(), message.messageId());

        switch (message.type()) {
            case ORDER -> {
                // TODO: Phase X — create draft sale / cart from WhatsApp catalog order
                log.info("WhatsApp router: ORDER received — draft sale creation not yet implemented");
            }
            case TEXT -> {
                if (orderReplyService.tryApply(message)) {
                    log.debug("WhatsApp router: TEXT handled as merchant reply command");
                } else {
                    log.info("WhatsApp router: TEXT received — no matching command");
                }
            }
            case BUTTON, INTERACTIVE -> {
                // TODO: Phase X — handle menu / reply selections
                log.info("WhatsApp router: {} received — menu handler not yet implemented", message.type());
            }
            case IMAGE, DOCUMENT, AUDIO, VIDEO, LOCATION, STICKER, REACTION, SYSTEM -> {
                log.debug("WhatsApp router: type={} handled by default logging", message.type());
            }
            case UNKNOWN -> {
                log.warn("WhatsApp router: unknown message type from={}", message.from());
            }
        }
    }
}
