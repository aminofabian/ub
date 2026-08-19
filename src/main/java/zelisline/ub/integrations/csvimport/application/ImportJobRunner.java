package zelisline.ub.integrations.csvimport.application;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportResponse;
import zelisline.ub.integrations.csvimport.domain.ImportJob;
import zelisline.ub.integrations.csvimport.repository.ImportJobRepository;
import zelisline.ub.integrations.csvimport.support.CsvImportProgressSink;

@Component
@RequiredArgsConstructor
@Slf4j
public class ImportJobRunner {

    private final ImportJobRepository importJobRepository;
    private final ImportJobPayloadStorage payloadStorage;
    private final ImportJobProgressWriter progressWriter;
    private final CsvImportApplicationService csvImportApplicationService;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    /** Single-queue drain suitable for a background ticker or integration tests. */
    public synchronized void processNext() {
        ImportJob job = importJobRepository.findFirstByStatusOrderByCreatedAtAsc(ImportJob.Status.pending).orElse(null);
        if (job == null) {
            return;
        }
        String jobId = job.getId();
        String payloadPath = job.getPayloadRelativePath();
        try {
            progressWriter.markProcessing(jobId);
            byte[] bytes = payloadStorage.readPayload(payloadPath);
            CsvImportProgressSink sink = new CsvImportProgressSink() {
                @Override
                public void onRowsParsed(int totalRowCount) {
                    progressWriter.onRowsParsed(jobId, totalRowCount);
                }

                @Override
                public void onRowCommitted(int rowsFinishedOneBased) {
                    progressWriter.onRowCommitted(jobId, rowsFinishedOneBased);
                }
            };
            switch (job.getKind()) {
                case items -> runItems(job, bytes, sink);
                case suppliers -> runSuppliers(job, bytes, sink);
                case opening_stock -> runOpening(job, bytes, sink);
            }
        } catch (ResponseStatusException ex) {
            String msg = ex.getReason() != null ? ex.getReason() : ex.getMessage();
            publishImportFailed(job, msg != null ? msg : ex.getStatusCode().toString());
            progressWriter.finalizeThrowable(jobId, msg != null ? msg : ex.getStatusCode().toString());
        } catch (RuntimeException | Error ex) {
            log.warn("import job crashed jobId={}", jobId, ex);
            publishImportFailed(job, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            progressWriter.finalizeThrowable(jobId, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.warn("import job IO/other failure jobId={}", jobId, ex);
            publishImportFailed(job, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            progressWriter.finalizeThrowable(jobId, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        } finally {
            payloadStorage.deleteQuietly(payloadPath);
        }
    }

    /**
     * Records a durable ERROR event when an import job crashes, so the
     * activity-log failures view surfaces broken imports per business.
     */
    private void publishImportFailed(ImportJob job, String message) {
        try {
            auditEventPublisher.publishSynchronous(auditEventBuilder
                    .builder(AuditEventCategory.SYSTEM, AuditEventTypes.IMPORT_JOB_FAILED, AuditEventSeverity.ERROR)
                    .businessId(job.getBusinessId())
                    .actor(job.getActorUserId(), job.getActorUserId() != null ? AuditEventActorType.USER : AuditEventActorType.SYSTEM)
                    .target("import_job", job.getId())
                    .targetLabel(job.getKind() != null ? job.getKind().name() : null)
                    .source("scheduler")
                    .reason(truncate(message, 500))
                    .metadata(Map.of("dryRun", String.valueOf(job.isDryRun()))).build());
        } catch (Exception ignored) {
            // Never fail the import because of an audit write.
        }
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    private void runItems(ImportJob job, byte[] bytes, CsvImportProgressSink sink) {
        if (job.isDryRun()) {
            CsvImportResponse res = csvImportApplicationService.dryRunItems(job.getBusinessId(), bytes, sink);
            progressWriter.finalizeDryRun(job.getId(), res);
            return;
        }
        CsvImportResponse res =
                csvImportApplicationService.commitItems(job.getBusinessId(), bytes, job.getActorUserId(), sink);
        if (!res.errors().isEmpty()) {
            progressWriter.finalizeCommitRejected(job.getId(), res.rowsParsed(), res.errors());
        } else {
            progressWriter.finalizeCommitOk(job.getId(), res.rowsParsed(),
                    res.rowsCommitted() != null ? res.rowsCommitted() : 0);
        }
    }

    private void runSuppliers(ImportJob job, byte[] bytes, CsvImportProgressSink sink) {
        if (job.isDryRun()) {
            CsvImportResponse res = csvImportApplicationService.dryRunSuppliers(job.getBusinessId(), bytes, sink);
            progressWriter.finalizeDryRun(job.getId(), res);
            return;
        }
        CsvImportResponse res = csvImportApplicationService.commitSuppliers(job.getBusinessId(), bytes, sink);
        if (!res.errors().isEmpty()) {
            progressWriter.finalizeCommitRejected(job.getId(), res.rowsParsed(), res.errors());
        } else {
            progressWriter.finalizeCommitOk(job.getId(), res.rowsParsed(),
                    res.rowsCommitted() != null ? res.rowsCommitted() : 0);
        }
    }

    private void runOpening(ImportJob job, byte[] bytes, CsvImportProgressSink sink) {
        if (job.isDryRun()) {
            CsvImportResponse res = csvImportApplicationService.dryRunOpeningStock(job.getBusinessId(), bytes, sink);
            progressWriter.finalizeDryRun(job.getId(), res);
            return;
        }
        CsvImportResponse res = csvImportApplicationService.commitOpeningStock(
                job.getBusinessId(),
                bytes,
                job.getActorUserId(),
                sink);
        if (!res.errors().isEmpty()) {
            progressWriter.finalizeCommitRejected(job.getId(), res.rowsParsed(), res.errors());
        } else {
            progressWriter.finalizeCommitOk(job.getId(), res.rowsParsed(),
                    res.rowsCommitted() != null ? res.rowsCommitted() : 0);
        }
    }
}
