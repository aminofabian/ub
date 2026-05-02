package zelisline.ub.catalog.api.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RegisterItemImageRequest(
        @NotBlank
        @Size(max = 500)
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

        /** When true, sets the item's {@code imageKey} to {@code s3Key} (cover / listing thumbnail). */
        Boolean primary
) {
}
