package zelisline.ub.exports.scheduler;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.exports.application.ReportDeliveryService;
import zelisline.ub.exports.domain.ReportSubscription;
import zelisline.ub.exports.repository.ReportSubscriptionRepository;
import zelisline.ub.exports.scheduler.ReportScheduleMath.Frequency;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.domain.TenantStatus;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Minute tick that delivers due report subscriptions at each business's local send time.
 * Mirrors {@code RestockDigestScheduler} (minute tick, per-tenant local-clock claim, catch-up
 * window). The period is claimed before delivery via {@code last_sent_period} so a redeploy or
 * a second instance cannot double-send; a transient delivery failure is logged (owner can
 * "Send now"), not auto-retried for that period.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
        name = "app.reports.delivery.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ReportDeliveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportDeliveryScheduler.class);
    private static final int CATCH_UP_MINUTES = 90;
    private static final int PAGE_SIZE = 200;

    private final BusinessRepository businessRepository;
    private final ReportSubscriptionRepository subscriptionRepository;
    private final ReportDeliveryService deliveryService;

    @Scheduled(cron = "0 * * * * *", zone = "UTC")
    public void tick() {
        int page = 0;
        int delivered = 0;
        int failed = 0;
        while (true) {
            var batch = businessRepository.findByDeletedAtIsNull(PageRequest.of(page, PAGE_SIZE));
            for (Business business : batch.getContent()) {
                if (business.getTenantStatus() == TenantStatus.SUSPENDED
                        || business.getTenantStatus() == TenantStatus.INACTIVE) {
                    continue;
                }
                var subs = subscriptionRepository.findByBusinessIdAndActiveTrue(business.getId());
                if (subs.isEmpty()) {
                    continue;
                }
                ZoneId zone = resolveZone(business);
                LocalDateTime now = LocalDateTime.now(zone).withSecond(0).withNano(0);
                LocalDate today = now.toLocalDate();
                for (ReportSubscription sub : subs) {
                    try {
                        if (deliverIfDue(sub, now, today)) {
                            delivered++;
                        }
                    } catch (RuntimeException ex) {
                        failed++;
                        log.warn("report delivery failed sub={} business={}",
                                sub.getId(), business.getId(), ex);
                    }
                }
            }
            if (!batch.hasNext()) {
                break;
            }
            page++;
        }
        if (delivered > 0 || failed > 0) {
            log.info("report delivery tick: delivered={} failed={}", delivered, failed);
        }
    }

    private boolean deliverIfDue(ReportSubscription sub, LocalDateTime now, LocalDate today) {
        Frequency freq;
        LocalTime sendTime;
        try {
            freq = ReportDeliveryService.parseFrequency(sub.getFrequency());
            sendTime = LocalTime.parse(sub.getSendTime());
        } catch (RuntimeException ex) {
            return false;
        }
        if (!ReportScheduleMath.isDue(freq, now, sendTime, sub.getDayOfWeek(), sub.getDayOfMonth(), CATCH_UP_MINUTES)) {
            return false;
        }
        String periodKey = ReportScheduleMath.periodKey(freq, today);
        if (periodKey.equals(sub.getLastSentPeriod())) {
            return false;
        }
        if (!deliveryService.claimPeriod(sub.getId(), periodKey)) {
            return false;
        }
        LocalDate[] window = ReportScheduleMath.window(freq, today);
        ReportDeliveryService.DeliveryOutcome outcome = deliveryService.deliver(sub, window[0], window[1]);
        if (!outcome.anySent()) {
            log.warn("report delivery produced nothing sub={} business={} failed={}",
                    sub.getId(), sub.getBusinessId(), outcome.failed());
            return false;
        }
        return true;
    }

    private static ZoneId resolveZone(Business business) {
        String tz = business.getTimezone();
        if (tz == null || tz.isBlank()) {
            return ZoneId.of("Africa/Nairobi");
        }
        try {
            return ZoneId.of(tz);
        } catch (RuntimeException ex) {
            return ZoneId.of("Africa/Nairobi");
        }
    }
}
