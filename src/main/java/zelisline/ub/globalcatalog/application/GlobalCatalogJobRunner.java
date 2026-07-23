package zelisline.ub.globalcatalog.application;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.globalcatalog.api.dto.AdoptRequest;
import zelisline.ub.globalcatalog.api.dto.AdoptResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteResponse;
import zelisline.ub.globalcatalog.domain.GlobalCatalogJob;
import zelisline.ub.globalcatalog.repository.GlobalCatalogJobRepository;

/**
 * Drains {@code global_catalog_jobs}. Claiming a pending row is brief and synchronized;
 * the heavy adopt/promote work runs on a small worker pool so one long Cloudinary-heavy
 * job cannot leave every other tenant import stuck in {@code pending} (UI: "Lining up…").
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class GlobalCatalogJobRunner {

    private static final int WORKER_THREADS = 2;
    private static final long PROGRESS_WRITE_INTERVAL_MS = 750;
    /** Processing jobs with no progress heartbeat older than this are failed so the queue moves on. */
    private static final long STALE_PROCESSING_TIMEOUT_MS = 20 * 60 * 1000L;

    private final GlobalCatalogJobRepository jobRepository;
    private final GlobalCatalogJobProgressWriter progressWriter;
    private final GlobalCatalogService globalCatalogService;
    private final SuperAdminGlobalCatalogPromoteService promoteService;
    private final ObjectMapper objectMapper;

    private final AtomicInteger workerSeq = new AtomicInteger();
    private final ExecutorService workers = Executors.newFixedThreadPool(WORKER_THREADS, threadFactory());

    private ThreadFactory threadFactory() {
        return runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("global-catalog-job-" + workerSeq.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    @PreDestroy
    void shutdownWorkers() {
        workers.shutdownNow();
    }

    /**
     * Claim at most one pending job and run it asynchronously. Safe to call from the
     * scheduler on a fixed delay — claiming does not wait for the previous job to finish.
     */
    public void processNext() {
        reapStaleProcessingJobs();
        ClaimedJob claimed = claimNextPending();
        if (claimed == null) {
            return;
        }
        workers.execute(() -> executeClaimed(claimed));
    }

    /**
     * Test helper: claim and run one job on the calling thread (deterministic ITs).
     */
    public void processNextBlocking() {
        reapStaleProcessingJobs();
        ClaimedJob claimed = claimNextPending();
        if (claimed == null) {
            return;
        }
        executeClaimed(claimed);
    }

    private void reapStaleProcessingJobs() {
        Instant cutoff = Instant.now().minusMillis(STALE_PROCESSING_TIMEOUT_MS);
        List<GlobalCatalogJob> stale = jobRepository.findByStatusAndUpdatedAtBefore(
                GlobalCatalogJob.Status.processing, cutoff);
        for (GlobalCatalogJob job : stale) {
            log.warn("Reaping stale global catalog job id={} updatedAt={}", job.getId(), job.getUpdatedAt());
            progressWriter.finalizeFailed(
                    job.getId(),
                    "Timed out — no progress for 20 minutes. Retry the import.");
        }
    }

    private synchronized ClaimedJob claimNextPending() {
        GlobalCatalogJob job = jobRepository
                .findFirstByStatusOrderByCreatedAtAsc(GlobalCatalogJob.Status.pending)
                .orElse(null);
        if (job == null) {
            return null;
        }
        int total;
        try {
            total = estimateRowsTotal(job);
        } catch (Exception ex) {
            progressWriter.finalizeFailed(
                    job.getId(),
                    "Invalid job payload: " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            return null;
        }
        progressWriter.markProcessing(job.getId(), total);
        return new ClaimedJob(job.getId(), job.getKind(), job.getBusinessId(), job.getActorUserId(), job.getPayloadJson(), total);
    }

    private void executeClaimed(ClaimedJob claimed) {
        String jobId = claimed.jobId();
        try {
            switch (claimed.kind()) {
                case adopt -> runAdopt(claimed);
                case promote -> runPromote(claimed);
            }
        } catch (ResponseStatusException ex) {
            String msg = ex.getReason() != null ? ex.getReason() : ex.getMessage();
            progressWriter.finalizeFailed(jobId, msg != null ? msg : ex.getStatusCode().toString());
        } catch (RuntimeException | Error ex) {
            log.warn("global catalog job crashed jobId={}", jobId, ex);
            progressWriter.finalizeFailed(
                    jobId, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.warn("global catalog job failure jobId={}", jobId, ex);
            progressWriter.finalizeFailed(
                    jobId, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        }
    }

    private int estimateRowsTotal(GlobalCatalogJob job) throws Exception {
        return switch (job.getKind()) {
            case adopt -> objectMapper.readValue(job.getPayloadJson(), AdoptRequest.class).lines().size();
            case promote -> objectMapper.readValue(job.getPayloadJson(), PromoteRequest.class).itemIds().size();
        };
    }

    private void runAdopt(ClaimedJob claimed) throws Exception {
        AdoptRequest request = objectMapper.readValue(claimed.payloadJson(), AdoptRequest.class);
        int total = claimed.rowsTotal();
        AdoptResponse response = globalCatalogService.adopt(
                claimed.businessId(),
                request,
                claimed.actorUserId(),
                throttledAdoptProgress(claimed.jobId(), total)
        );
        progressWriter.finalizeOk(
                claimed.jobId(),
                total,
                response.importedCount(),
                objectMapper.writeValueAsString(response),
                "Adopted " + response.importedCount() + ", skipped " + response.skippedCount()
        );
    }

    private void runPromote(ClaimedJob claimed) throws Exception {
        PromoteRequest request = objectMapper.readValue(claimed.payloadJson(), PromoteRequest.class);
        int total = claimed.rowsTotal();
        PromoteResponse response = promoteService.promoteForJob(
                request,
                claimed.actorUserId(),
                throttledPromoteProgress(claimed.jobId(), total));
        int committed = response.createdCount() + response.updatedCount();
        progressWriter.finalizeOk(
                claimed.jobId(),
                total,
                committed,
                objectMapper.writeValueAsString(response),
                "Promoted created=" + response.createdCount()
                        + " updated=" + response.updatedCount()
                        + " skipped=" + response.skippedCount()
        );
    }

    private GlobalCatalogService.AdoptProgressListener throttledAdoptProgress(String jobId, int total) {
        long[] lastWriteAt = {0L};
        return (processedCount, productName) -> {
            long now = System.currentTimeMillis();
            if (now - lastWriteAt[0] < PROGRESS_WRITE_INTERVAL_MS && processedCount < total) {
                return;
            }
            lastWriteAt[0] = now;
            String label = productName == null || productName.isBlank()
                    ? "Importing " + processedCount + " of " + total
                    : "Importing " + processedCount + " of " + total + " — " + productName;
            try {
                progressWriter.markProgress(jobId, processedCount, label);
            } catch (RuntimeException ex) {
                log.debug("progress write skipped for job {}: {}", jobId, ex.toString());
            }
        };
    }

    private SuperAdminGlobalCatalogPromoteService.PromoteProgressListener throttledPromoteProgress(
            String jobId,
            int total
    ) {
        long[] lastWriteAt = {0L};
        return (processedCount, itemName) -> {
            long now = System.currentTimeMillis();
            if (now - lastWriteAt[0] < PROGRESS_WRITE_INTERVAL_MS && processedCount < total) {
                return;
            }
            lastWriteAt[0] = now;
            String label = itemName == null || itemName.isBlank()
                    ? "Promoting " + processedCount + " of " + total
                    : "Promoting " + processedCount + " of " + total + " — " + itemName;
            try {
                progressWriter.markProgress(jobId, processedCount, label);
            } catch (RuntimeException ex) {
                log.debug("progress write skipped for job {}: {}", jobId, ex.toString());
            }
        };
    }

    private record ClaimedJob(
            String jobId,
            GlobalCatalogJob.Kind kind,
            String businessId,
            String actorUserId,
            String payloadJson,
            int rowsTotal
    ) {
    }
}
