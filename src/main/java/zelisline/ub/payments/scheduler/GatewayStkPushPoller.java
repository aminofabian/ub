package zelisline.ub.payments.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import zelisline.ub.payments.application.GatewayStkPushService;
import zelisline.ub.payments.domain.GatewayStkPush;
import zelisline.ub.payments.domain.GatewayStkPushStatuses;
import zelisline.ub.payments.repository.GatewayStkPushRepository;

/**
 * Polls pending STK pushes when webhooks are delayed or missed (fallback confirmation).
 */
@Component
public class GatewayStkPushPoller {

    private static final Logger log = LoggerFactory.getLogger(GatewayStkPushPoller.class);

    /** Past this age a Buy Goods await can no longer be matched to a webhook. */
    private static final Duration TILL_AWAIT_MAX_AGE = Duration.ofMinutes(15);

    private final GatewayStkPushRepository pushRepository;
    private final GatewayStkPushService pushService;

    @Value("${app.payments.stk.poll.max-age-minutes:45}")
    private int maxAgeMinutes;

    @Value("${app.payments.stk.poll.max-attempts:40}")
    private int maxAttempts;

    public GatewayStkPushPoller(GatewayStkPushRepository pushRepository, GatewayStkPushService pushService) {
        this.pushRepository = pushRepository;
        this.pushService = pushService;
    }

    @Scheduled(fixedDelayString = "${app.payments.stk.poll.interval-ms:30000}")
    public void pollPending() {
        Instant cutoff = Instant.now().minus(maxAgeMinutes, ChronoUnit.MINUTES);
        List<GatewayStkPush> pending = pushRepository.findByStatusAndCreatedAtAfterOrderByCreatedAtAsc(
                GatewayStkPushStatuses.PENDING, cutoff);
        if (pending.isEmpty()) {
            return;
        }
        int polled = 0;
        for (GatewayStkPush push : pending) {
            // Till-awaits are settled by buygoods webhooks only — checking them at the
            // gateway would open a transaction per row on every run for nothing.
            if (!GatewayStkPushService.isPollableAtGateway(push)) {
                if (isPastTillAwaitWindow(push)) {
                    try {
                        pushService.expireStaleTillAwait(push, TILL_AWAIT_MAX_AGE);
                    } catch (Exception e) {
                        log.warn("Till await expiry failed for push={}: {}", push.getId(), e.getMessage());
                    }
                }
                continue;
            }
            if (push.getPollCount() >= maxAttempts) {
                try {
                    pushService.markTimedOutIfPollsExhausted(push, maxAttempts);
                } catch (Exception e) {
                    log.warn("STK expire after max polls failed for push={}: {}", push.getId(), e.getMessage());
                }
                continue;
            }
            try {
                pushService.pollAndUpdate(push);
                polled++;
            } catch (Exception e) {
                log.warn("STK poll failed for push={}: {}", push.getId(), e.getMessage());
            }
        }
        if (polled > 0) {
            log.debug("STK poll run: checked={} pending={}", polled, pending.size());
        }
    }

    private static boolean isPastTillAwaitWindow(GatewayStkPush push) {
        return push.getCreatedAt() != null
                && push.getCreatedAt().isBefore(Instant.now().minus(TILL_AWAIT_MAX_AGE));
    }
}
