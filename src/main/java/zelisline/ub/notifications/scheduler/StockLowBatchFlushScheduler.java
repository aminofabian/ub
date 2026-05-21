package zelisline.ub.notifications.scheduler;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.notifications.application.StockLowBatchService;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(name = "app.notifications.stock-low.flusher.enabled", havingValue = "true")
public class StockLowBatchFlushScheduler {

    private final StockLowBatchService stockLowBatchService;

    @Scheduled(
            fixedDelayString = "${app.notifications.stock-low.flusher.fixed-delay-ms:60000}",
            initialDelayString = "${app.notifications.stock-low.flusher.initial-delay-ms:30000}"
    )
    public void tick() {
        try {
            stockLowBatchService.flushDueBatches();
        } catch (RuntimeException ex) {
            log.warn("Stock low batch flush tick failed", ex);
        }
    }
}
