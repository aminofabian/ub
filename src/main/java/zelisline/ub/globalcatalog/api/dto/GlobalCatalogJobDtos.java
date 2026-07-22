package zelisline.ub.globalcatalog.api.dto;

import java.time.Instant;

public final class GlobalCatalogJobDtos {

    private GlobalCatalogJobDtos() {
    }

    public record CreateJobResponse(String jobId) {
    }

    public record JobResponse(
            String id,
            String kind,
            String status,
            String businessId,
            Integer rowsTotal,
            int rowsProcessed,
            Integer rowsCommitted,
            String statusMessage,
            Object result,
            Instant createdAt,
            Instant completedAt
    ) {
    }
}
