package zelisline.ub.catalog.api.dto;

import java.time.Instant;

public record ItemImageResponse(
        String id,
        /** Legacy object key; for Cloudinary rows often mirrors {@link #publicId()}. */
        String s3Key,
        String secureUrl,
        String publicId,
        String provider,
        Integer width,
        Integer height,
        int sortOrder,
        String contentType,
        String altText,
        Long bytes,
        String format,
        String assetSignature,
        /** Dominant color (e.g. from Cloudinary <code>colors</code> analysis) for UI flourishes. */
        String predominantColorHex,
        /** Perceptual hash for duplicate / similarity workflows. */
        String phash,
        Instant createdAt
) {
}
