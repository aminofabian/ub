package zelisline.ub.payroll.scheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.application.PayrollAutomationService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.payroll.automation.enabled", havingValue = "true", matchIfMissing = true)
public class PayrollAutomationScheduler {

    private static final Logger log = LoggerFactory.getLogger(PayrollAutomationScheduler.class);

    private final PayrollAutomationService payrollAutomationService;

    @Scheduled(
            cron = "${app.payroll.automation.tick-cron:0 * * * * *}",
            zone = "${app.payroll.automation.zone:Africa/Nairobi}")
    public void tick() {
        var summary = payrollAutomationService.runScheduledPayroll();
        if (summary.businesses() > 0) {
            log.info(
                    "Payroll automation tick: businesses={} autoPaid={} reminded={} failed={}",
                    summary.businesses(),
                    summary.autoPaid(),
                    summary.reminded(),
                    summary.failed());
        }
    }
}
