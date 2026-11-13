package zelisline.ub.posdraft.application;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.application.BusinessCreditMessagingSettingsService;
import zelisline.ub.messaging.application.TenantMessagingConfig;
import zelisline.ub.messaging.domain.SmsSendReason;
import zelisline.ub.messaging.infrastructure.SmsMessagingClient;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.posdraft.domain.PosDraft;
import zelisline.ub.posdraft.repository.PosDraftRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Texts customers shortly before a booking. Only bookings whose staff member ticked "send a
 * reminder" are texted, through the shop's own metered SMS; a shop with no SMS set up is skipped.
 *
 * <p>Each reminder is claimed in the database before it is sent, so a customer is never texted
 * twice — at the price that a text the provider then fails to deliver is not retried.</p>
 */
@Service
@RequiredArgsConstructor
public class ServiceReminderService {

    private static final Logger log = LoggerFactory.getLogger(ServiceReminderService.class);
    private static final int BATCH = 200;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);

    private final PosDraftRepository draftRepository;
    private final BusinessRepository businessRepository;
    private final StaffProfileService staffProfileService;
    private final BusinessCreditMessagingSettingsService messagingSettingsService;
    private final SmsMessagingClient smsMessagingClient;
    private final TransactionTemplate transactionTemplate;

    /** How long before the booking the text goes out. */
    @Value("${app.services.reminders.lead-minutes:120}")
    private int leadMinutes;

    /** @return how many reminders were handed to the SMS provider */
    public int sendDue() {
        Instant now = Instant.now();
        // A booking made in the last half hour is fresh in the customer's mind; do not text it.
        List<PosDraft> due = draftRepository.findDueReminders(
                now, now.plus(leadMinutes, ChronoUnit.MINUTES), now.minus(30, ChronoUnit.MINUTES),
                PageRequest.of(0, BATCH));
        Map<String, TenantMessagingConfig> configs = new HashMap<>();
        int sent = 0;
        for (PosDraft booking : due) {
            try {
                TenantMessagingConfig config = configs.computeIfAbsent(booking.getBusinessId(),
                        id -> messagingSettingsService.resolveForDispatch(id, SmsSendReason.NOTIFICATION));
                if (!config.enabled() || !config.smsConfigured()) {
                    continue;
                }
                String digits = StkPhoneNormalizer.normalize(booking.getBookingPhone());
                if (digits == null) {
                    continue;
                }
                Integer claimed = transactionTemplate.execute(
                        status -> draftRepository.claimReminder(booking.getId(), Instant.now()));
                if (claimed == null || claimed != 1) {
                    continue;
                }
                SmsMessagingClient.SendResult result =
                        smsMessagingClient.sendText(config, "+" + digits, message(booking));
                if (result.sent() || result.stub()) {
                    sent++;
                }
            } catch (RuntimeException ex) {
                // Never the phone number or the name: only which booking failed.
                log.warn("Booking reminder failed for ticket {}: {}", booking.getId(), ex.getClass().getSimpleName());
            }
        }
        return sent;
    }

    String message(PosDraft booking) {
        Business business = businessRepository.findById(booking.getBusinessId()).orElse(null);
        String shop = business != null && business.getName() != null ? business.getName().trim() : "the shop";
        ZoneId zone = zone(business);
        ZonedDateTime at = booking.getScheduledStart().atZone(zone);
        boolean today = at.toLocalDate().equals(ZonedDateTime.now(zone).toLocalDate());
        String when = (today ? "today" : "on " + DAY.format(at)) + " at " + TIME.format(at);
        String with = "";
        if (booking.getRequestedStaffProfileId() != null) {
            String name = staffProfileService
                    .displayNamesByIds(booking.getBusinessId(), List.of(booking.getRequestedStaffProfileId()))
                    .get(booking.getRequestedStaffProfileId());
            if (name != null && !name.isBlank()) {
                with = " with " + name.trim();
            }
        }
        String first = firstName(booking.getBookingName());
        return (first == null ? "Hello" : "Hi " + first) + ", a reminder of your booking at " + shop + " " + when
                + with + ". See you then.";
    }

    private static ZoneId zone(Business business) {
        try {
            return ZoneId.of(business != null && business.getTimezone() != null
                    ? business.getTimezone() : "Africa/Nairobi");
        } catch (RuntimeException ex) {
            return ZoneId.of("Africa/Nairobi");
        }
    }

    private static String firstName(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        String first = name.trim().split("\\s+")[0];
        return first.length() > 30 ? first.substring(0, 30) : first;
    }
}
