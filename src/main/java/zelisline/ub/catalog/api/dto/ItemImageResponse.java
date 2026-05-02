package zelisline.ub.catalog.api.dto;

import java.time.Instant;

public record ItemImageResponse(
        String id,
        String s3Key,
        Integer width,
        Integer height,
        int sortOrder,
        String contentType,
        String altText,
        Instant createdAt
) {
}
