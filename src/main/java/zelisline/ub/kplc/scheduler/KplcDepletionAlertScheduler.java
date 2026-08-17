package zelisline.ub.kplc.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.kplc.application.KplcDepletionAlertService;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.kplc.depletion-alerts.enabled", havingValue = "true", matchIfMissing = true)
public class KplcDepletionAlertScheduler {

    private final KplcDepletionAlertService depletionAlertService;

    @Scheduled(
            cron = "${app.kplc.depletion-alerts.cron:0 0 8 * * *}",
            zone = "${app.kplc.depletion-alerts.zone:Africa/Nairobi}"
    )
    public void sweep() {
        depletionAlertService.sweep();
    }
}
