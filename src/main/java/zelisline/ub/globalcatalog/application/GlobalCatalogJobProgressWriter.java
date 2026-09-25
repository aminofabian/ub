package zelisline.ub.globalcatalog.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.domain.GlobalCatalogJob;
import zelisline.ub.globalcatalog.repository.GlobalCatalogJobRepository;

@Service
@RequiredArgsConstructor
public class GlobalCatalogJobProgressWriter {

    private final GlobalCatalogJobRepository jobRepository;

    /**
     * Claims a pending job for this instance. Returns {@code true} only for the caller that won the
     * atomic {@code pending -> processing} transition; a losing instance gets {@code false} and must
     * not run the job. This is what keeps a multi-instance deployment from executing it twice.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean claimPending(String jobId, int rowsTotal) {
        return jobRepository.claimPending(
                jobId,
                GlobalCatalogJob.Status.pending,
                GlobalCatalogJob.Status.processing,
                rowsTotal,
                "Processing",
                Instant.now()) > 0;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProgress(String jobId, int rowsProcessed, String message) {
        GlobalCatalogJob job = load(jobId);
        job.setRowsProcessed(rowsProcessed);
        job.setStatusMessage(truncate(message, 1000));
        jobRepository.save(job);
    }

    /**
     * Marks the job completed only while it is still {@code processing}. Returns {@code false} when
     * the row was already finalised (e.g. reaped as stale), so a late finish cannot clobber a
     * terminal state.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finalizeOk(String jobId, int rowsTotal, int rowsCommitted, String resultJson, String message) {
        return jobRepository.completeJob(
                jobId,
                GlobalCatalogJob.Status.processing,
                GlobalCatalogJob.Status.completed,
                rowsTotal,
                rowsCommitted,
                resultJson,
                truncate(message, 1000),
                Instant.now()) > 0;
    }

    /**
     * Fails the job from any non-terminal state (pending for invalid payloads, processing for
     * crashes/timeouts), but never overwrites a completed or already-failed row.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean finalizeFailed(String jobId, String message) {
        return jobRepository.failJob(
                jobId,
                List.of(GlobalCatalogJob.Status.pending, GlobalCatalogJob.Status.processing),
                GlobalCatalogJob.Status.failed,
                truncate(message, 1000),
                Instant.now()) > 0;
    }

    private GlobalCatalogJob load(String jobId) {
        return jobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("global catalog job missing: " + jobId));
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
