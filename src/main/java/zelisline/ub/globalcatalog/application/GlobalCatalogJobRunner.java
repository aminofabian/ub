package zelisline.ub.globalcatalog.application;

import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.globalcatalog.api.dto.AdoptRequest;
import zelisline.ub.globalcatalog.api.dto.AdoptResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteResponse;
import zelisline.ub.globalcatalog.domain.GlobalCatalogJob;
import zelisline.ub.globalcatalog.repository.GlobalCatalogJobRepository;

@Component
@RequiredArgsConstructor
@Slf4j
public class GlobalCatalogJobRunner {

    private final GlobalCatalogJobRepository jobRepository;
    private final GlobalCatalogJobProgressWriter progressWriter;
    private final GlobalCatalogService globalCatalogService;
    private final SuperAdminGlobalCatalogPromoteService promoteService;
    private final ObjectMapper objectMapper;

    /** Single-queue drain suitable for a background ticker or integration tests. */
    public synchronized void processNext() {
        GlobalCatalogJob job = jobRepository
                .findFirstByStatusOrderByCreatedAtAsc(GlobalCatalogJob.Status.pending)
                .orElse(null);
        if (job == null) {
            return;
        }
        String jobId = job.getId();
        try {
            switch (job.getKind()) {
                case adopt -> runAdopt(job);
                case promote -> runPromote(job);
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

    private void runAdopt(GlobalCatalogJob job) throws Exception {
        AdoptRequest request = objectMapper.readValue(job.getPayloadJson(), AdoptRequest.class);
        int total = request.lines().size();
        progressWriter.markProcessing(job.getId(), total);
        AdoptResponse response = globalCatalogService.adopt(
                job.getBusinessId(),
                request,
                job.getActorUserId()
        );
        progressWriter.finalizeOk(
                job.getId(),
                total,
                response.importedCount(),
                objectMapper.writeValueAsString(response),
                "Adopted " + response.importedCount() + ", skipped " + response.skippedCount()
        );
    }

    private void runPromote(GlobalCatalogJob job) throws Exception {
        PromoteRequest request = objectMapper.readValue(job.getPayloadJson(), PromoteRequest.class);
        int total = request.itemIds().size();
        progressWriter.markProcessing(job.getId(), total);
        PromoteResponse response = promoteService.promoteForJob(request, job.getActorUserId());
        int committed = response.createdCount() + response.updatedCount();
        progressWriter.finalizeOk(
                job.getId(),
                total,
                committed,
                objectMapper.writeValueAsString(response),
                "Promoted created=" + response.createdCount()
                        + " updated=" + response.updatedCount()
                        + " skipped=" + response.skippedCount()
        );
    }
}
