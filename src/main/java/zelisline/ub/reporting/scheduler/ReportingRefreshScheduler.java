package zelisline.ub.reporting.scheduler;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;
import zelisline.ub.reporting.application.ReportingMvRefresher;
import zelisline.ub.reporting.application.ReportingRefreshRunner;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Periodic tick that refreshes every registered {@link ReportingMvRefresher} for
 * every active tenant (Phase 7 Slice 1). Disabled by default — only enabled when
 * {@code app.reporting.refresh.enabled=true} so {@code @SpringBootTest} suites and
 * fresh dev workstations don't accidentally activate it.
 *
 * <p>v1 strategy is full per-tenant refresh on a global cron (see PHASE_7_PLAN.md
 * Locked ADRs); local-midnight bucketing for {@code mv_inventory_snapshot} arrives
 * with Slice 4.</p>
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.reporting.refresh.enabled", havingValue = "true")
public class ReportingRefreshScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReportingRefreshScheduler.class);
    private static final int TENANT_PAGE_SIZE = 200;

    private final List<ReportingMvRefresher> refreshers;
    private final ReportingRefreshRunner runner;
    private final BusinessRepository businessRepository;

    @Scheduled(cron = "${app.reporting.refresh.cron:0 5 * * * *}", zone = "${app.reporting.refresh.zone:UTC}")
    public void tick() {
        if (refreshers.isEmpty()) {
            log.debug("Reporting refresh tick: no refreshers registered, skipping");
            return;
        }
        int totalRuns = 0;
        int totalFailed = 0;
        int page = 0;
        while (true) {
            var batch = businessRepository.findByDeletedAtIsNull(PageRequest.of(page, TENANT_PAGE_SIZE));
            for (Business business : batch.getContent()) {
                for (ReportingMvRefresher refresher : refreshers) {
                    try {
                        runner.run(refresher, business.getId());
                    } catch (RuntimeException ex) {
                        totalFailed++;
                    }
                    totalRuns++;
                }
            }
            if (!batch.hasNext()) {
                break;
            }
            page++;
        }
        log.info("Reporting refresh tick complete: runs={} failed={} refreshers={}",
                totalRuns, totalFailed, refreshers.size());
    }
}
