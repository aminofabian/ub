package zelisline.ub.platform.api.dto;

import java.time.Instant;

/** Secrets never returned — use {@code has*} flags. */
public record MediaStorageSettingsResponse(
        String uploadProvider,
        /** True when uploads are actually going to R2 right now (provider r2 and credentials readable). */
        boolean r2Active,
        String r2AccountId,
        String r2Endpoint,
        String r2Bucket,
        boolean hasR2AccessKeyId,
        boolean hasR2SecretAccessKey,
        String r2PublicBaseUrl,
        boolean secretsReadable,
        String secretsError,
        boolean encryptionEphemeral,
        Instant updatedAt
) {}
