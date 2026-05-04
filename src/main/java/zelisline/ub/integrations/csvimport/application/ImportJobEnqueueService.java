package zelisline.ub.integrations.csvimport.application;

import java.io.IOException;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.csvimport.api.dto.CreateImportJobResponse;
import zelisline.ub.integrations.csvimport.api.dto.ImportJobResponse;
import zelisline.ub.integrations.csvimport.domain.ImportJob;
import zelisline.ub.integrations.csvimport.repository.ImportJobRepository;

@Service
@RequiredArgsConstructor
public class ImportJobEnqueueService {

    private final ImportJobRepository importJobRepository;
    private final ImportJobPayloadStorage payloadStorage;
    private final ObjectMapper objectMapper;

    @Transactional
    public CreateImportJobResponse enqueue(
            ImportJob.Kind kind,
            boolean dryRun,
            String businessId,
            String actorUserId,
            String originalFilename,
            byte[] csvBytes
    ) throws IOException {
        String id = UUID.randomUUID().toString();
        String relativePath = payloadStorage.persistPayload(id, csvBytes);
        ImportJob j = new ImportJob();
        j.setId(id);
        j.setBusinessId(businessId);
        j.setKind(kind);
        j.setDryRun(dryRun);
        j.setActorUserId(actorUserId);
        j.setOriginalFilename(originalFilename);
        j.setPayloadRelativePath(relativePath);
        j.setStatus(ImportJob.Status.pending);
        importJobRepository.save(j);
        return new CreateImportJobResponse(id);
    }

    @Transactional(readOnly = true)
    public ImportJobResponse get(String jobId, String businessId) {
        ImportJob job = importJobRepository.findByIdAndBusinessId(jobId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Import job not found"));
        return ImportJobResponse.fromEntity(job, objectMapper);
    }
}
