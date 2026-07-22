package zelisline.ub.globalcatalog.application;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import zelisline.ub.globalcatalog.api.dto.AdoptRequest;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogJobDtos.CreateJobResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogJobDtos.JobResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteRequest;
import zelisline.ub.globalcatalog.domain.GlobalCatalogJob;
import zelisline.ub.globalcatalog.repository.GlobalCatalogJobRepository;

@Service
@RequiredArgsConstructor
public class GlobalCatalogJobService {

    public static final int SYNC_ADOPT_MAX_LINES = 25;
    public static final int JOB_ADOPT_MAX_LINES = 500;
    public static final int SYNC_PROMOTE_MAX_ITEMS = 100;
    public static final int JOB_PROMOTE_MAX_ITEMS = 500;

    private final GlobalCatalogJobRepository jobRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public CreateJobResponse enqueueAdopt(String businessId, String actorUserId, AdoptRequest request) {
        int lines = request.lines() == null ? 0 : request.lines().size();
        if (lines < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Adopt job requires at least one line");
        }
        if (lines > JOB_ADOPT_MAX_LINES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Adopt job limited to " + JOB_ADOPT_MAX_LINES + " lines");
        }
        return enqueue(GlobalCatalogJob.Kind.adopt, businessId, actorUserId, request);
    }

    @Transactional
    public CreateJobResponse enqueuePromote(String actorUserId, PromoteRequest request) {
        int items = request.itemIds() == null ? 0 : request.itemIds().size();
        if (items < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Promote job requires at least one item");
        }
        if (items > JOB_PROMOTE_MAX_ITEMS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Promote job limited to " + JOB_PROMOTE_MAX_ITEMS + " items");
        }
        if (actorUserId == null || actorUserId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Super-admin session required");
        }
        return enqueue(GlobalCatalogJob.Kind.promote, null, actorUserId, request);
    }

    @Transactional(readOnly = true)
    public JobResponse getAdoptJob(String jobId, String businessId) {
        GlobalCatalogJob job = jobRepository.findByIdAndBusinessId(jobId, businessId)
                .filter(j -> j.getKind() == GlobalCatalogJob.Kind.adopt)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Adopt job not found"));
        return toResponse(job);
    }

    @Transactional(readOnly = true)
    public JobResponse getPromoteJob(String jobId) {
        GlobalCatalogJob job = jobRepository.findByIdAndBusinessIdIsNull(jobId)
                .filter(j -> j.getKind() == GlobalCatalogJob.Kind.promote)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Promote job not found"));
        return toResponse(job);
    }

    public static void requireSyncAdoptSize(AdoptRequest request) {
        int lines = request.lines() == null ? 0 : request.lines().size();
        if (lines > SYNC_ADOPT_MAX_LINES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Sync adopt limited to " + SYNC_ADOPT_MAX_LINES
                            + " lines; use POST /api/v1/global-catalog/adopt/jobs");
        }
    }

    public static void requireSyncPromoteSize(PromoteRequest request) {
        int items = request.itemIds() == null ? 0 : request.itemIds().size();
        if (items > SYNC_PROMOTE_MAX_ITEMS) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Sync promote limited to " + SYNC_PROMOTE_MAX_ITEMS
                            + " items; use POST /api/v1/super-admin/global-catalog/promote/jobs");
        }
    }

    private CreateJobResponse enqueue(
            GlobalCatalogJob.Kind kind,
            String businessId,
            String actorUserId,
            Object payload
    ) {
        GlobalCatalogJob job = new GlobalCatalogJob();
        job.setId(UUID.randomUUID().toString());
        job.setKind(kind);
        job.setStatus(GlobalCatalogJob.Status.pending);
        job.setBusinessId(businessId);
        job.setActorUserId(actorUserId);
        job.setPayloadJson(writeJson(payload));
        job.setRowsProcessed(0);
        jobRepository.save(job);
        return new CreateJobResponse(job.getId());
    }

    private JobResponse toResponse(GlobalCatalogJob job) {
        Object result = null;
        if (job.getResultJson() != null && !job.getResultJson().isBlank()) {
            try {
                result = objectMapper.readValue(job.getResultJson(), Object.class);
            } catch (JsonProcessingException ignored) {
                result = job.getResultJson();
            }
        }
        return new JobResponse(
                job.getId(),
                job.getKind().name(),
                job.getStatus().name(),
                job.getBusinessId(),
                job.getRowsTotal(),
                job.getRowsProcessed(),
                job.getRowsCommitted(),
                job.getStatusMessage(),
                result,
                job.getCreatedAt(),
                job.getCompletedAt()
        );
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid job payload");
        }
    }
}
