package zelisline.ub.wholesale.application;

import java.time.LocalDate;
import java.time.ZoneId;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/** Places the standing orders that are due. Hourly; an order runs once on its date. */
@Component
@RequiredArgsConstructor
public class StandingOrderRunner {

    private static final Logger log = LoggerFactory.getLogger(StandingOrderRunner.class);
    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private final StandingOrderService standingOrderService;

    @Scheduled(
            fixedDelayString = "${app.wholesale.standing-orders.sweep-ms:3600000}",
            initialDelayString = "${app.wholesale.standing-orders.sweep-initial-ms:600000}")
    public void run() {
        try {
            int placed = standingOrderService.runDue(LocalDate.now(NAIROBI));
            if (placed > 0) {
                log.info("standing orders: placed {} order(s)", placed);
            }
        } catch (RuntimeException e) {
            log.warn("standing orders run failed: {}", e.getMessage());
        }
    }
}
