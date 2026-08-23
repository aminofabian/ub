package zelisline.ub.messaging.application;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.credits.domain.BusinessCreditSettings;
import zelisline.ub.credits.repository.BusinessCreditSettingsRepository;
import zelisline.ub.messaging.domain.WhatsAppMessage;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.storefront.application.WebOrderFulfillmentService;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.repository.WebOrderRepository;
import zelisline.ub.tenancy.application.StorefrontSettingsService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * V2 slice — inbound merchant replies drive fulfillment status (scope §19).
 * <p>
 * The merchant runs their shop from the chat they already live in:
 * {@code CONFIRM 5F6A7B8C}, {@code READY 5F6A7B8C} or {@code COMPLETE 5F6A7B8C}
 * map to the same {@code fulfillmentStatus} transitions as the dashboard.
 * <p>
 * Security: the business is resolved from Meta's {@code phone_number_id}
 * (indexed via credit settings; sender-number scan as fallback) and the sender
 * must be the shop's own WhatsApp checkout number — a customer cannot advance
 * an order by guessing a code.
 */
@Service
@RequiredArgsConstructor
public class WhatsAppOrderReplyService {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppOrderReplyService.class);

    private static final int RECENT_ORDERS_SCAN = 300;

    private static final Pattern COMMAND_PATTERN =
            Pattern.compile("^(CONFIRM|READY|DISPATCH|COMPLETE)\\s+([A-Z0-9-]{4,16})$");

    private final BusinessRepository businessRepository;
    private final BusinessCreditSettingsRepository creditSettingsRepository;
    private final StorefrontSettingsService storefrontSettingsService;
    private final WebOrderRepository webOrderRepository;
    private final WebOrderFulfillmentService fulfillmentService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final CustomerMessageDispatcher customerMessageDispatcher;

    enum ReplyCommand {
        CONFIRM,
        READY,
        DISPATCH,
        COMPLETE
    }

    record ParsedCommand(ReplyCommand command, String code) {
    }

    static ParsedCommand parse(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher m = COMMAND_PATTERN.matcher(content.trim().toUpperCase(Locale.ROOT));
        if (!m.matches()) {
            return null;
        }
        return new ParsedCommand(ReplyCommand.valueOf(m.group(1)), m.group(2));
    }

    /**
     * Attempts to apply a merchant reply command. Returns {@code true} when the
     * message was recognized as one (even if it was rejected/ignored), so the
     * router does not treat it as ordinary customer text.
     */
    @Transactional
    public boolean tryApply(WhatsAppMessage message) {
        if (message == null || message.from() == null || message.from().isBlank()) {
            return false;
        }
        ParsedCommand cmd = parse(message.content());
        if (cmd == null) {
            return false;
        }
        String senderDigits = message.from().replaceAll("\\D", "");
        if (senderDigits.length() < 9) {
            return false;
        }

        Business business = resolveBusiness(message.phoneNumberId(), senderDigits).orElse(null);
        if (business == null) {
            log.info("WhatsApp reply: no shop matched sender={} command={} code={} — ignoring",
                    senderDigits, cmd.command(), cmd.code());
            return true;
        }
        if (!isMerchantSender(business, senderDigits)) {
            log.warn("WhatsApp reply: sender={} is not the shop's WhatsApp number — ignoring command for business={}",
                    senderDigits, business.getId());
            return true;
        }

        WebOrder order = findOrderByCode(business.getId(), cmd.code());
        if (order == null) {
            log.info("WhatsApp reply: no recent order for code={} business={} — ignoring", cmd.code(), business.getId());
            return true;
        }
        String target = fulfillmentTarget(cmd.command());
        try {
            fulfillmentService.advance(business.getId(), order.getId(), target);
            log.info("WhatsApp reply: order {} advanced to {} by merchant via WhatsApp", order.getId(), target);
            replyToMerchant(business, senderDigits, order, target);
        } catch (Exception ex) {
            log.warn("WhatsApp reply: could not advance order {} to {} ({}): {}",
                    order.getId(), target, cmd.command(), ex.getMessage());
        }
        return true;
    }

    /** Indexed by Meta phone_number_id first; sender-scan fallback for shops on the shared platform number. */
    private Optional<Business> resolveBusiness(String phoneNumberId, String senderDigits) {
        if (phoneNumberId != null && !phoneNumberId.isBlank()) {
            Optional<Business> viaPhoneId = creditSettingsRepository
                    .findByWhatsappMetaPhoneNumberId(phoneNumberId.trim())
                    .map(BusinessCreditSettings::getBusinessId)
                    .flatMap(businessRepository::findById);
            if (viaPhoneId.isPresent()) {
                return viaPhoneId;
            }
        }
        // Fallback: the shop's checkout number matches the sender. Only reached for
        // command-shaped texts (callers parse first), so the scan stays bounded.
        for (Business business : businessRepository.findAll()) {
            if (senderDigits.equals(whatsAppDigitsOf(business))) {
                return Optional.of(business);
            }
        }
        return Optional.empty();
    }

    private boolean isMerchantSender(Business business, String senderDigits) {
        String shopDigits = whatsAppDigitsOf(business);
        return shopDigits != null && shopDigits.equals(senderDigits);
    }

    private String whatsAppDigitsOf(Business business) {
        try {
            var settings = storefrontSettingsService.readFromSettingsJson(business.getSettings());
            String number = settings != null && settings.whatsappCheckout() != null
                    ? settings.whatsappCheckout().number()
                    : null;
            if (number == null || number.isBlank()) {
                return null;
            }
            String digits = number.replaceAll("\\D", "");
            if (digits.startsWith("0") && digits.length() >= 9) {
                digits = "254" + digits.substring(1);
            }
            return digits.length() >= 9 ? digits : null;
        } catch (Exception ex) {
            return null;
        }
    }

    private WebOrder findOrderByCode(String businessId, String code) {
        return webOrderRepository
                .findByBusinessIdOrderByCreatedAtDesc(businessId, PageRequest.of(0, RECENT_ORDERS_SCAN))
                .stream()
                .filter(o -> WebOrderCodes.matches(code, o.getId()))
                .findFirst()
                .orElse(null);
    }

    private static String fulfillmentTarget(ReplyCommand command) {
        return switch (command) {
            case CONFIRM -> "confirmed";
            case READY, DISPATCH -> "dispatched";
            case COMPLETE -> "completed";
        };
    }

    /** Best-effort ack inside the 24h session window (the merchant just messaged us). */
    private void replyToMerchant(Business business, String toDigits, WebOrder order, String target) {
        try {
            TenantMessagingConfig cfg = messagingSettingsService.resolveForDispatch(business.getId());
            String code = WebOrderCodes.code(order.getId());
            String body = switch (target) {
                case "confirmed" -> "Order " + code + " confirmed ✓ — stock reserved, pickup can begin.";
                case "dispatched" -> "Order " + code + " marked ready for pickup ✓";
                case "completed" -> "Order " + code + " completed ✓ Karibu tena!";
                default -> "Order " + code + " updated ✓";
            };
            customerMessageDispatcher.deliverDirect(cfg, toDigits, body);
        } catch (Exception ex) {
            log.debug("WhatsApp reply ack failed (non-fatal): {}", ex.getMessage());
        }
    }
}
