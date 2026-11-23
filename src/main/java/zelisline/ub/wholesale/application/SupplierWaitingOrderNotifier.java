package zelisline.ub.wholesale.application;

import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.application.NotificationService;
import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.tenancy.application.FeatureFlagService;
import zelisline.ub.wholesale.domain.SalesOrder;
import zelisline.ub.wholesale.domain.SalesOrderStatuses;
import zelisline.ub.wholesale.domain.SupplierNotificationLog;
import zelisline.ub.wholesale.repository.SalesOrderRepository;
import zelisline.ub.wholesale.repository.SupplierNotificationLogRepository;

/**
 * The only note supplier engagement sends: orders that have waited more than a working day for an answer. One
 * email to the shop's owner, at most once a day, on a working morning. Never about money, never a reminder to
 * keep anything going, and buyers get none. Off unless {@code supplier_engagement.notifications} is on.
 */
@Component
@RequiredArgsConstructor
public class SupplierWaitingOrderNotifier {

    private static final Logger log = LoggerFactory.getLogger(SupplierWaitingOrderNotifier.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    static final LocalTime FROM = LocalTime.of(9, 0);
    static final LocalTime UNTIL = LocalTime.of(12, 0);

    private final SalesOrderRepository salesOrderRepository;
    private final SupplierNotificationLogRepository logRepository;
    private final FeatureFlagService featureFlagService;
    private final UserRepository userRepository;
    private final NotificationService notificationService;

    @Value("${supplier-engagement.kill-switch:false}")
    private boolean killSwitch;

    @Scheduled(
            fixedDelayString = "${supplier-engagement.notifications.sweep-ms:1800000}",
            initialDelayString = "${supplier-engagement.notifications.sweep-initial-ms:900000}")
    public void run() {
        try {
            int sent = sendDue(ZonedDateTime.now(NAIROBI));
            if (sent > 0) {
                log.info("supplier engagement: sent {} waiting-order note(s)", sent);
            }
        } catch (RuntimeException e) {
            log.warn("supplier engagement notes failed: {}", e.getMessage());
        }
    }

    /** Sends what is due at this moment. Returns how many notes went out. */
    int sendDue(ZonedDateTime now) {
        if (killSwitch || !canSendAt(now)) {
            return 0;
        }
        LocalDate today = now.toLocalDate();
        // Anything placed in the last hour cannot have waited a working day; this keeps the list short.
        List<String> businessIds = salesOrderRepository.findBusinessIdsWithOrdersPlacedBefore(
                SalesOrderStatuses.PLACED, now.toInstant().minus(1, ChronoUnit.HOURS));
        int sent = 0;
        for (String businessId : businessIds) {
            try {
                if (sendFor(businessId, today, now.toInstant())) {
                    sent++;
                }
            } catch (RuntimeException e) {
                log.warn("supplier engagement note failed for {}: {}", businessId, e.getMessage());
            }
        }
        return sent;
    }

    /** A working morning in Nairobi: Monday to Saturday, not a public holiday, between 9 and 12. */
    static boolean canSendAt(ZonedDateTime now) {
        LocalDate day = now.toLocalDate();
        LocalTime time = now.toLocalTime();
        return now.getDayOfWeek() != DayOfWeek.SUNDAY
                && !KenyaPublicHolidays.between(day, day).contains(day)
                && !time.isBefore(FROM)
                && time.isBefore(UNTIL);
    }

    private boolean sendFor(String businessId, LocalDate today, Instant now) {
        if (!featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT)
                || !featureFlagService.isEnabled(businessId, FeatureFlagService.FLAG_SUPPLIER_ENGAGEMENT_NOTIFICATIONS)) {
            return false;
        }
        SupplierNotificationLog last = logRepository.findById(businessId).orElse(null);
        if (last != null && !last.getLastSentOn().isBefore(today)) {
            return false;
        }
        long waiting = salesOrderRepository
                .findTop200ByBusinessIdAndStatusOrderByCreatedAtAsc(businessId, SalesOrderStatuses.PLACED).stream()
                .map(SalesOrder::getCreatedAt)
                .filter(placed -> WorkingHours.between(placed, now) > SupplierStandingService.HOURS_PER_WORKING_DAY)
                .count();
        if (waiting == 0) {
            return false;
        }
        String to = userRepository.findOwnersByBusinessIdIn(List.of(businessId)).stream()
                .map(User::getEmail)
                .filter(email -> email != null && !email.isBlank() && !email.endsWith(".invalid"))
                .findFirst()
                .orElse(null);
        if (to == null) {
            return false;
        }
        String line = waiting == 1
                ? "1 trade order has waited more than a working day for your answer."
                : waiting + " trade orders have waited more than a working day for your answer.";
        String text = line + "\n\nOpen your trade desk to answer: Orders, To answer.";
        String html = "<p>" + line + "</p><p>Open your trade desk to answer: Orders, To answer.</p>";
        notificationService.sendNotificationEmail(to, "Trade orders waiting for your answer", text, html);
        // Written after the send: if the send throws, the note is tried again on the next sweep.
        if (last == null) {
            logRepository.save(new SupplierNotificationLog(businessId, today));
        } else {
            last.setLastSentOn(today);
            logRepository.save(last);
        }
        return true;
    }
}
