package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record RegisterItemImageRequest(
        @Size(max = 512)
        String s3Key,

        @Min(0)
        @Max(9999)
        Integer sortOrder,

        @Min(1)
        @Max(32767)
        Integer width,

        @Min(1)
        @Max(32767)
        Integer height,

        @Size(max = 128)
        String contentType,

        @Size(max = 500)
        String altText,

        Boolean primary,

        /** Cloudinary HTTPS delivery URL; use with {@code cloudinaryPublicId}. */
        @Size(max = 2048)
        String secureUrl,

        @Size(max = 512)
        String cloudinaryPublicId,

        Long bytes,

        @Size(max = 32)
        String format,

        @Size(max = 80)
        String assetSignature,

        @Size(max = 16)
        String predominantColorHex,

        @Size(max = 64)
        String phash
) {
}
