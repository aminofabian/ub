package zelisline.ub.messaging.application;

import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.domain.CustomerPhone;
import zelisline.ub.credits.domain.KenyanPhoneForms;
import zelisline.ub.credits.repository.CustomerPhoneRepository;
import zelisline.ub.messaging.domain.SmsMessage;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.repository.SmsMessageRepository;

/**
 * Writes and reads the per-message SMS log (SMS Hub, ADR 0013). Recording runs in its
 * own transaction and never throws to the sender: a logging failure must not fail or
 * roll back a send that has already reached the provider.
 */
@Service
@RequiredArgsConstructor
public class SmsMessageLogService {

    private static final Logger log = LoggerFactory.getLogger(SmsMessageLogService.class);
    private static final List<String> ACCEPTED = List.of(SmsMessage.STATUS_SENT, SmsMessage.STATUS_DELIVERED);
    private static final int MAX_BODY = 1600;
    private static final int MAX_ERROR = 500;
    public static final String BODY_NOT_STORED = "[message text not stored]";

    private final SmsMessageRepository repository;
    private final CustomerPhoneRepository customerPhoneRepository;

    /**
     * Whether the text itself is kept. Many SMS carry secrets (sign-in codes, phone
     * verification, drawer and till approval codes), and the log is readable by staff,
     * so the text is stored only when the sender has declared it customer-facing by
     * opening an {@link SmsSendContext}, or the reason is one that never carries a code.
     * Everything else is logged for cost and delivery with the text left out.
     */
    static boolean storesBody(SmsSendReason reason, SmsSendContext.Values ctx) {
        if (reason == SmsSendReason.OTP) {
            return false;
        }
        return ctx != null
                || reason == SmsSendReason.CAMPAIGN
                || reason == SmsSendReason.AUTOMATION
                || reason == SmsSendReason.CREDIT_REMINDER;
    }

    /** What the send choke point knows about one attempt. */
    public record Attempt(
            String businessId,
            String toE164,
            String body,
            int parts,
            int credits,
            SmsSendReason reason,
            String referenceId,
            String provider,
            String providerMessageId,
            String status,
            String error
    ) {
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(Attempt attempt) {
        try {
            String phone = digits(attempt.toE164());
            if (attempt.businessId() == null || attempt.businessId().isBlank() || phone.isEmpty()) {
                return;
            }
            SmsSendContext.Values ctx = SmsSendContext.current();
            SmsMessage row = new SmsMessage();
            row.setBusinessId(attempt.businessId());
            row.setPhone(phone);
            row.setBody(storesBody(attempt.reason(), ctx)
                    ? truncate(attempt.body(), MAX_BODY)
                    : BODY_NOT_STORED);
            row.setParts(Math.max(1, attempt.parts()));
            row.setCredits(Math.max(0, attempt.credits()));
            row.setReason(attempt.reason() != null ? attempt.reason().code() : null);
            row.setReferenceId(truncate(attempt.referenceId(), 128));
            row.setProvider(attempt.provider());
            row.setProviderMessageId(truncate(attempt.providerMessageId(), 128));
            row.setStatus(attempt.status());
            row.setError(truncate(attempt.error(), MAX_ERROR));
            if (ctx != null) {
                row.setCustomerId(ctx.customerId());
                row.setCampaignId(ctx.campaignId());
                row.setMessageClass(ctx.promotional()
                        ? SmsMessage.CLASS_PROMOTIONAL
                        : SmsMessage.CLASS_TRANSACTIONAL);
            }
            if (row.getCustomerId() == null) {
                row.setCustomerId(customerIdForPhone(attempt.businessId(), phone));
            }
            repository.save(row);
        } catch (RuntimeException ex) {
            log.warn("sms_message log write failed business={}: {}", attempt.businessId(), ex.getMessage());
        }
    }

    /** True when a text with this reference was already accepted by the provider. */
    @Transactional(readOnly = true)
    public boolean alreadySent(String businessId, String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            return false;
        }
        return repository.existsByBusinessIdAndReferenceIdAndStatusIn(businessId, referenceId, ACCEPTED);
    }

    /** Promotional texts this shop has sent to a number since {@code since}. */
    @Transactional(readOnly = true)
    public long promotionalSince(String businessId, String phoneDigits, Instant since) {
        return repository.countPromotionalSince(businessId, digits(phoneDigits), since);
    }

    @Transactional(readOnly = true)
    public Page<SmsMessage> page(String businessId, String customerId, String campaignId, Pageable pageable) {
        if (campaignId != null && !campaignId.isBlank()) {
            return repository.findByBusinessIdAndCampaignIdOrderByCreatedAtDesc(businessId, campaignId, pageable);
        }
        if (customerId != null && !customerId.isBlank()) {
            return repository.findByBusinessIdAndCustomerIdOrderByCreatedAtDesc(businessId, customerId, pageable);
        }
        return repository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable);
    }

    /** Sent count and credits since {@code since}: {@code [messages, credits]}. */
    @Transactional(readOnly = true)
    public long[] totalsSince(String businessId, Instant since) {
        List<Object[]> rows = repository.sentTotalsSince(businessId, since);
        if (rows.isEmpty() || rows.get(0) == null) {
            return new long[] {0L, 0L};
        }
        Object[] row = rows.get(0);
        return new long[] {asLong(row[0]), asLong(row[1])};
    }

    /** Rows of {@code [reason, messages, credits]} since {@code since}. */
    @Transactional(readOnly = true)
    public List<Object[]> totalsByReasonSince(String businessId, Instant since) {
        return repository.sentTotalsByReasonSince(businessId, since);
    }

    private String customerIdForPhone(String businessId, String phoneDigits) {
        List<String> forms = KenyanPhoneForms.lookupCandidates(phoneDigits);
        if (forms.isEmpty()) {
            return null;
        }
        List<CustomerPhone> matches = customerPhoneRepository.findByBusinessIdAndPhoneIn(businessId, forms);
        return matches.isEmpty() ? null : matches.get(0).getCustomerId();
    }

    public static long asLong(Object value) {
        return value instanceof Number n ? n.longValue() : 0L;
    }

    public static String digits(String phone) {
        return phone == null ? "" : phone.replaceAll("\\D+", "");
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() > max ? s.substring(0, max) : s;
    }
}
