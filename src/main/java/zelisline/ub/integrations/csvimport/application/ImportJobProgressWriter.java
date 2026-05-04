package zelisline.ub.integrations.csvimport.application;

import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportLineError;
import zelisline.ub.integrations.csvimport.api.dto.CsvImportResponse;
import zelisline.ub.integrations.csvimport.domain.ImportJob;
import zelisline.ub.integrations.csvimport.repository.ImportJobRepository;

@Service
@RequiredArgsConstructor
public class ImportJobProgressWriter {

    private static final int MAX_ERRORS_JSON_ROWS = 200;

    private final ImportJobRepository importJobRepository;
    private final ObjectMapper objectMapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markProcessing(String jobId) {
        ImportJob j = load(jobId);
        j.setStatus(ImportJob.Status.processing);
        j.setRowsProcessed(0);
        importJobRepository.save(j);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRowsParsed(String jobId, int total) {
        ImportJob j = load(jobId);
        j.setRowsTotal(total);
        j.setRowsProcessed(0);
        importJobRepository.save(j);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void onRowCommitted(String jobId, int rowsProcessedOneBased) {
        ImportJob j = load(jobId);
        j.setRowsProcessed(rowsProcessedOneBased);
        importJobRepository.save(j);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeDryRun(String jobId, CsvImportResponse response) {
        ImportJob j = load(jobId);
        j.setStatus(ImportJob.Status.completed);
        j.setRowsTotal(response.rowsParsed());
        j.setRowsProcessed(response.rowsParsed());
        j.setRowsCommitted(null);
        j.setErrorsJson(safeErrorsJson(response.errors()));
        j.setStatusMessage(response.errors().isEmpty() ? "Dry-run OK" : "Dry-run finished with validation issues");
        j.setCompletedAt(Instant.now());
        importJobRepository.save(j);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeCommitOk(String jobId, int rowsParsed, int rowsCommitted) {
        ImportJob j = load(jobId);
        j.setStatus(ImportJob.Status.completed);
        j.setRowsTotal(rowsParsed);
        j.setRowsProcessed(rowsParsed);
        j.setRowsCommitted(rowsCommitted);
        j.setErrorsJson(null);
        j.setStatusMessage("Committed");
        j.setCompletedAt(Instant.now());
        importJobRepository.save(j);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeCommitRejected(String jobId, int rowsParsed, List<CsvImportLineError> errors) {
        ImportJob j = load(jobId);
        j.setStatus(ImportJob.Status.failed);
        j.setRowsTotal(rowsParsed);
        j.setRowsProcessed(0);
        j.setRowsCommitted(null);
        j.setErrorsJson(safeErrorsJson(errors));
        j.setStatusMessage("Validation failed before commit");
        j.setCompletedAt(Instant.now());
        importJobRepository.save(j);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finalizeThrowable(String jobId, String message) {
        ImportJob j = load(jobId);
        j.setStatus(ImportJob.Status.failed);
        j.setStatusMessage(truncate(message, 1000));
        j.setCompletedAt(Instant.now());
        importJobRepository.save(j);
    }

    private ImportJob load(String jobId) {
        return importJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("import job missing: " + jobId));
    }

    private String safeErrorsJson(List<CsvImportLineError> errors) {
        if (errors == null || errors.isEmpty()) {
            return null;
        }
        List<CsvImportLineError> capped =
                errors.size() > MAX_ERRORS_JSON_ROWS ? errors.subList(0, MAX_ERRORS_JSON_ROWS) : errors;
        try {
            return objectMapper.writeValueAsString(capped);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
