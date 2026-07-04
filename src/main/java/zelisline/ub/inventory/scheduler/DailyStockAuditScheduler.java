package zelisline.ub.inventory.scheduler;

import java.time.LocalDate;
import java.time.ZoneId;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.inventory.application.DailyStockAuditService;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(
        name = "app.inventory.daily-stock-audit.enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class DailyStockAuditScheduler {

    private final DailyStockAuditService dailyStockAuditService;

    @Value("${app.inventory.daily-stock-audit.zone:Africa/Nairobi}")
    private String zoneId;

    @Scheduled(
            cron = "${app.inventory.daily-stock-audit.cron:0 0 6 * * *}",
            zone = "${app.inventory.daily-stock-audit.zone:Africa/Nairobi}"
    )
    public void generateDailyAudits() {
        LocalDate auditDate = LocalDate.now(ZoneId.of(zoneId));
        log.info("Generating daily stock audits for auditDate={}", auditDate);
        try {
            dailyStockAuditService.generateForAllBusinesses(auditDate);
        } catch (RuntimeException ex) {
            log.warn("Daily stock audit generation failed for auditDate={}", auditDate, ex);
        }
    }
}
