package zelisline.ub.credits.sms.application;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.Customer;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.credits.repository.CustomerRepository;
import zelisline.ub.credits.sms.domain.SmsMarketingStatus;
import zelisline.ub.messaging.application.SmsMessageLogService;
import zelisline.ub.messaging.domain.SmsMessage;
import zelisline.ub.messaging.repository.SmsMessageRepository;

/**
 * Handles SMS provider callbacks (SMS Hub, ADR 0013): delivery reports update the
 * message log; an inbound STOP / ONDOKA / TOKA opts the sender out, JOIN / YES / NDIYO
 * opts them in.
 *
 * <p>Callbacks are public and unauthenticated, so the worst a forged one can do is mark
 * a message delivered or failed, or change one number's marketing consent. Kiosk sends
 * from a shared platform sender, so a reply cannot name the shop it answers:
 * <ul>
 *   <li>STOP applies to every shop that sent this number a promotion in the last 30
 *       days; if none did, to every shop that holds the number (opting out is always safe);</li>
 *   <li>JOIN applies only when exactly one shop texted this number in the last 7 days,
 *       because consent must be given to a specific shop.</li>
 * </ul>
 * Field names differ per provider and are matched tolerantly; confirm against a real
 * payload from each provider before relying on delivery reports.
 */
@Service
@RequiredArgsConstructor
public class SmsInboundService {

    private static final Logger log = LoggerFactory.getLogger(SmsInboundService.class);

    private static final Set<String> STOP_WORDS = Set.of("STOP", "ONDOKA", "TOKA", "UNSUBSCRIBE", "ACHA");
    private static final Set<String> JOIN_WORDS = Set.of("JOIN", "YES", "NDIYO", "START", "SUBSCRIBE");

    private static final List<String> ID_KEYS =
            List.of("messageid", "message_id", "msgid", "id", "smsid", "sms_id");
    private static final List<String> STATUS_KEYS =
            List.of("status", "deliverystatus", "delivery_status", "dlrstatus", "dlr_status", "state");
    private static final List<String> FROM_KEYS =
            List.of("from", "msisdn", "sender", "phone", "mobile", "source");
    private static final List<String> TEXT_KEYS =
            List.of("message", "text", "body", "content", "sms");

    private final SmsMessageRepository smsMessageRepository;
    private final CustomerPhoneRepository customerPhoneRepository;
    private final CustomerRepository customerRepository;
    private final CustomerSmsConsentService consentService;

    public enum Keyword {
        STOP, JOIN, NONE
    }

    /** First word of an inbound text, mapped to an action. */
    public static Keyword keyword(String text) {
        if (text == null || text.isBlank()) {
            return Keyword.NONE;
        }
        String first = text.trim().split("\\s+")[0].replaceAll("[^\\p{L}]", "").toUpperCase(Locale.ROOT);
        if (STOP_WORDS.contains(first)) {
            return Keyword.STOP;
        }
        if (JOIN_WORDS.contains(first)) {
            return Keyword.JOIN;
        }
        return Keyword.NONE;
    }

    /** Message status for a provider's delivery word, or null when it is not final. */
    public static String deliveryStatus(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String v = raw.trim().toLowerCase(Locale.ROOT);
        if (v.contains("undeliv") || v.contains("fail") || v.contains("reject")
                || v.contains("expired") || v.contains("blacklist") || v.contains("invalid")) {
            return SmsMessage.STATUS_FAILED;
        }
        if (v.contains("deliv") || v.equals("success") || v.equals("delivrd")) {
            return SmsMessage.STATUS_DELIVERED;
        }
        return null;
    }

    /**
     * @param fields callback fields with lower-cased keys
     * @return number of log rows updated
     */
    @Transactional
    public int handleDelivery(Map<String, String> fields) {
        String messageId = first(fields, ID_KEYS);
        String status = deliveryStatus(first(fields, STATUS_KEYS));
        if (messageId == null || status == null) {
            return 0;
        }
        List<SmsMessage> rows = smsMessageRepository.findByProviderMessageId(messageId);
        int updated = 0;
        for (SmsMessage row : rows) {
            if (SmsMessage.STATUS_DELIVERED.equals(row.getStatus())) {
                continue;
            }
            row.setStatus(status);
            if (SmsMessage.STATUS_DELIVERED.equals(status)) {
                row.setDeliveredAt(Instant.now());
            } else {
                row.setError("provider_reported_failed");
            }
            smsMessageRepository.save(row);
            updated++;
        }
        return updated;
    }

    /**
     * @param fields callback fields with lower-cased keys
     * @return number of customers whose consent changed
     */
    @Transactional
    public int handleInbound(Map<String, String> fields) {
        String phone = SmsMessageLogService.digits(first(fields, FROM_KEYS));
        Keyword keyword = keyword(first(fields, TEXT_KEYS));
        if (phone.isEmpty() || keyword == Keyword.NONE) {
            return 0;
        }
        String msisdn = KenyanPhoneForms.toMsisdn254(phone);
        if (msisdn == null) {
            return 0;
        }
        List<String> forms = KenyanPhoneForms.lookupCandidates(msisdn);
        List<CustomerPhone> holders = customerPhoneRepository.findByPhoneIn(forms);
        if (holders.isEmpty()) {
            return 0;
        }
        Instant now = Instant.now();
        Set<String> businessIds;
        String status;
        if (keyword == Keyword.STOP) {
            status = SmsMarketingStatus.OPTED_OUT;
            businessIds = new LinkedHashSet<>(smsMessageRepository.businessIdsThatTexted(
                    msisdn, now.minus(Duration.ofDays(30)), true));
            if (businessIds.isEmpty()) {
                for (CustomerPhone holder : holders) {
                    businessIds.add(holder.getBusinessId());
                }
            }
        } else {
            status = SmsMarketingStatus.OPTED_IN;
            businessIds = new LinkedHashSet<>(smsMessageRepository.businessIdsThatTexted(
                    msisdn, now.minus(Duration.ofDays(7)), false));
            if (businessIds.size() != 1) {
                log.info("sms_inbound join ignored: {} candidate shops", businessIds.size());
                return 0;
            }
        }
        int changed = 0;
        for (CustomerPhone holder : holders) {
            if (!businessIds.contains(holder.getBusinessId())) {
                continue;
            }
            Customer customer = customerRepository
                    .findByIdAndBusinessIdAndDeletedAtIsNull(holder.getCustomerId(), holder.getBusinessId())
                    .orElse(null);
            if (consentService.apply(customer, status, SmsMarketingStatus.SOURCE_SMS_KEYWORD)) {
                changed++;
            }
        }
        log.info("sms_inbound keyword={} shops={} customers_changed={}", keyword, businessIds.size(), changed);
        return changed;
    }

    private static String first(Map<String, String> fields, List<String> keys) {
        if (fields == null) {
            return null;
        }
        for (String key : keys) {
            String value = fields.get(key);
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return null;
    }
}
