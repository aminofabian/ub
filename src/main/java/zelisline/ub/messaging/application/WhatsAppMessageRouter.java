package zelisline.ub.messaging.application;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import zelisline.ub.messaging.domain.WhatsAppMessage;

/**
 * Routes inbound WhatsApp messages to the appropriate handler.
 *
 * <p>Current implementation logs all messages for observability.
 * Future extensions:
 * <ul>
 *   <li>{@code ORDER} → create a draft sale/order in Palmart</li>
 *   <li>{@code TEXT} → customer service bot or command parser</li>
 *   <li>{@code BUTTON}/{@code INTERACTIVE} → handle menu selections</li>
 * </ul>
 */
@Component
public class WhatsAppMessageRouter {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppMessageRouter.class);

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
                // TODO: Phase X — customer service bot / command parser
                log.info("WhatsApp router: TEXT received — bot/command parser not yet implemented");
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
