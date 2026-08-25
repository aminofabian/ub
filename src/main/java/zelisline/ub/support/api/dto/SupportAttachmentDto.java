package zelisline.ub.support.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Cloudinary-hosted file attached to a support message. */
public record SupportAttachmentDto(
        @NotBlank @Size(max = 1024) String url,
        @Size(max = 512) String publicId,
        @Size(max = 255) String fileName,
        @Size(max = 128) String contentType,
        Long bytes
) {}
