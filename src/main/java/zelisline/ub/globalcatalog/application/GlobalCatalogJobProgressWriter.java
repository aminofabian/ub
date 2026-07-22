package zelisline.ub.globalcatalog.application;

import java.time.Instant;

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

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(String jobId, int rowsTotal) {
        GlobalCatalogJob job = load(jobId);
        job.setStatus(GlobalCatalogJob.Status.processing);
        job.setRowsTotal(rowsTotal);
        job.setRowsProcessed(0);
        job.setStatusMessage("Processing");
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeOk(String jobId, int rowsTotal, int rowsCommitted, String resultJson, String message) {
        GlobalCatalogJob job = load(jobId);
        job.setStatus(GlobalCatalogJob.Status.completed);
        job.setRowsTotal(rowsTotal);
        job.setRowsProcessed(rowsTotal);
        job.setRowsCommitted(rowsCommitted);
        job.setResultJson(resultJson);
        job.setStatusMessage(truncate(message, 1000));
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeFailed(String jobId, String message) {
        GlobalCatalogJob job = load(jobId);
        job.setStatus(GlobalCatalogJob.Status.failed);
        job.setStatusMessage(truncate(message, 1000));
        job.setCompletedAt(Instant.now());
        jobRepository.save(job);
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
