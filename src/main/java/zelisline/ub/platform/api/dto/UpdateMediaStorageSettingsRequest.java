package zelisline.ub.platform.api.dto;

import jakarta.validation.constraints.Size;

/**
 * Secret fields: {@code null} = leave unchanged; blank string = clear stored value.
 * Other fields: {@code null} = leave unchanged; blank string = clear.
 * Switching {@code uploadProvider} to {@code r2} first verifies the bucket is writable.
 */
public record UpdateMediaStorageSettingsRequest(
        @Size(max = 16) String uploadProvider,
        @Size(max = 64) String r2AccountId,
        @Size(max = 512) String r2Endpoint,
        @Size(max = 128) String r2Bucket,
        @Size(max = 256) String r2AccessKeyId,
        @Size(max = 256) String r2SecretAccessKey,
        @Size(max = 512) String r2PublicBaseUrl
) {}
