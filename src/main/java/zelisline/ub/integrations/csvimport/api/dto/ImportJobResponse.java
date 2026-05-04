package zelisline.ub.integrations.csvimport.api.dto;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.integrations.csvimport.domain.ImportJob;

public record ImportJobResponse(
        String id,
        String kind,
        boolean dryRun,
        String status,
        Integer rowsTotal,
        int rowsProcessed,
        Integer rowsCommitted,
        List<CsvImportLineError> errors,
        String statusMessage,
        Instant createdAt,
        Instant completedAt
) {
    public static ImportJobResponse fromEntity(ImportJob job, ObjectMapper mapper) {
        List<CsvImportLineError> errs = List.of();
        if (job.getErrorsJson() != null && !job.getErrorsJson().isBlank()) {
            try {
                errs = mapper.readValue(job.getErrorsJson(), new TypeReference<List<CsvImportLineError>>() {});
            } catch (JsonProcessingException ignored) {
                errs = List.of();
            }
        }
        return new ImportJobResponse(
                job.getId(),
                job.getKind().name(),
                job.isDryRun(),
                job.getStatus().name(),
                job.getRowsTotal(),
                job.getRowsProcessed(),
                job.getRowsCommitted(),
                Collections.unmodifiableList(errs),
                job.getStatusMessage(),
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }
}
