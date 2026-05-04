package zelisline.ub.integrations.csvimport.application;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            progressWriter.finalizeThrowable(jobId, msg != null ? msg : ex.getStatusCode().toString());
        } catch (RuntimeException | Error ex) {
            log.warn("import job crashed jobId={}", jobId, ex);
            progressWriter.finalizeThrowable(jobId, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        } catch (Exception ex) {
            log.warn("import job IO/other failure jobId={}", jobId, ex);
            progressWriter.finalizeThrowable(jobId, ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
        } finally {
            payloadStorage.deleteQuietly(payloadPath);
        }
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
