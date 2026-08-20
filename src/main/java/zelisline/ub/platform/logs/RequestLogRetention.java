package zelisline.ub.platform.logs;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * Keeps {@code platform_request_log} bounded: one bulk purge a day removes
 * rows older than {@code app.platform-request-log.retention-days} (default 14).
 */
@Component
@RequiredArgsConstructor
public class RequestLogRetention {

    private static final Logger log = LoggerFactory.getLogger(RequestLogRetention.class);

    private final PlatformRequestLogRepository repository;

    @Value("${app.platform-request-log.retention-days:14}")
    private int retentionDays;

    @Scheduled(cron = "${app.platform-request-log.retention-cron:0 30 3 * * *}")
    public void purge() {
        Instant cutoff = Instant.now().minus(Duration.ofDays(Math.max(1, retentionDays)));
        try {
            long removed = repository.deleteByLoggedAtBefore(cutoff);
            if (removed > 0) {
                log.info("Purged {} platform request log rows older than {} days",
                        removed, Math.max(1, retentionDays));
            }
        } catch (Exception e) {
            log.warn("Platform request log purge failed: {}", e.getMessage());
        }
    }
}
