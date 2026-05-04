package zelisline.ub.reporting.application;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import zelisline.ub.reporting.domain.ReportingRefreshRun;
import zelisline.ub.reporting.repository.ReportingRefreshRunRepository;

/**
 * Single entry point for executing one refresh of one summary table for one tenant
 * (Phase 7 Slice 1). Persists a {@code reporting_refresh_runs} row whether the
 * refresh succeeds or throws — the lag metric the {@code Risks} section in the plan
 * depends on is built off this audit table.
 */
@Service
@RequiredArgsConstructor
public class ReportingRefreshRunner {

    private static final Logger log = LoggerFactory.getLogger(ReportingRefreshRunner.class);
    private static final int ERROR_MAX = 1990;

    private final ReportingRefreshRunRepository runRepository;

    public ReportingRefreshRun run(ReportingMvRefresher refresher, String businessId) {
        ReportingRefreshRun run = startRun(refresher, businessId);
        try {
            long rows = refresher.refresh(businessId);
            return finishOk(run, rows);
        } catch (RuntimeException ex) {
            log.error("MV refresh failed mv={} business={}", refresher.mvName(), businessId, ex);
            finishFailed(run, ex);
            throw ex;
        }
    }

    private ReportingRefreshRun startRun(ReportingMvRefresher refresher, String businessId) {
        ReportingRefreshRun run = new ReportingRefreshRun();
        run.setId(UUID.randomUUID().toString());
        run.setMvName(refresher.mvName());
        run.setBusinessId(businessId);
        run.setStatus("running");
        run.setStartedAt(Instant.now());
        return runRepository.save(run);
    }

    private ReportingRefreshRun finishOk(ReportingRefreshRun run, long rows) {
        Instant now = Instant.now();
        run.setStatus("success");
        run.setRowsChanged(rows);
        run.setFinishedAt(now);
        run.setDurationMs(Math.max(0L, now.toEpochMilli() - run.getStartedAt().toEpochMilli()));
        return runRepository.save(run);
    }

    private void finishFailed(ReportingRefreshRun run, Throwable ex) {
        Instant now = Instant.now();
        run.setStatus("failed");
        run.setFinishedAt(now);
        run.setDurationMs(Math.max(0L, now.toEpochMilli() - run.getStartedAt().toEpochMilli()));
        String message = ex.getClass().getSimpleName() + ": " + (ex.getMessage() == null ? "" : ex.getMessage());
        if (message.length() > ERROR_MAX) {
            message = message.substring(0, ERROR_MAX);
        }
        run.setErrorMessage(message);
        runRepository.save(run);
    }
}
