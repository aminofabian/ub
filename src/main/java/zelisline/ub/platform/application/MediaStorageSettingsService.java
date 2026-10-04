package zelisline.ub.platform.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

import zelisline.ub.payments.infrastructure.CredentialEncryptionService;
import zelisline.ub.platform.api.dto.MediaStorageSettingsResponse;
import zelisline.ub.platform.api.dto.UpdateMediaStorageSettingsRequest;
import zelisline.ub.platform.domain.PlatformMediaStorageSettings;
import zelisline.ub.platform.media.ActiveR2ConnectionSource;
import zelisline.ub.platform.media.MediaStorageProvider;
import zelisline.ub.platform.media.R2BucketProbe;
import zelisline.ub.platform.media.R2Connection;
import zelisline.ub.platform.media.R2PublicUrlSource;
import zelisline.ub.platform.repository.PlatformMediaStorageSettingsRepository;

/**
 * Super-admin media storage settings. Switching uploads to R2 is refused unless the saved
 * credentials are complete and can write to the bucket; if they later become unreadable
 * (e.g. the encryption key changed) uploads fall back to Cloudinary rather than failing.
 */
@Service
@RequiredArgsConstructor
public class MediaStorageSettingsService implements ActiveR2ConnectionSource, R2PublicUrlSource {

    private static final Logger log = LoggerFactory.getLogger(MediaStorageSettingsService.class);
    private static final String URL_SCHEME_SEPARATOR = "://";
    private static final String DEFAULT_URL_SCHEME = "https" + URL_SCHEME_SEPARATOR;
    private static final String R2_HOST_SUFFIX = ".r2.cloudflarestorage.com";

    private final PlatformMediaStorageSettingsRepository repository;
    private final CredentialEncryptionService encryptionService;
    private final R2BucketProbe bucketProbe;

    @Transactional(readOnly = true)
    public MediaStorageSettingsResponse getForSuperAdmin() {
        return toResponse(loadOrDefault());
    }

    @Transactional
    public MediaStorageSettingsResponse update(UpdateMediaStorageSettingsRequest body) {
        PlatformMediaStorageSettings row = loadOrDefault();
        applyR2Fields(row, body);
        if (body.uploadProvider() != null) {
            MediaStorageProvider provider = MediaStorageProvider.fromCode(body.uploadProvider()).orElseThrow(() ->
                    new ResponseStatusException(HttpStatus.BAD_REQUEST, "uploadProvider must be cloudinary or r2"));
            row.setUploadProvider(provider.code());
        }
        if (providerOf(row) == MediaStorageProvider.R2) {
            bucketProbe.verify(requireConnection(row));
        }
        row.setUpdatedAt(Instant.now());
        return toResponse(repository.save(row));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<R2Connection> activeR2Connection() {
        PlatformMediaStorageSettings row = loadOrDefault();
        if (providerOf(row) != MediaStorageProvider.R2) {
            return Optional.empty();
        }
        ConnectionRead read = readConnection(row);
        if (read.connection() == null) {
            log.warn("Uploads are set to R2 but its settings are unusable ({}); using Cloudinary", read.problems());
        }
        return Optional.ofNullable(read.connection());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> r2PublicBaseUrl() {
        return Optional.ofNullable(asBaseUrl(loadOrDefault().getR2PublicBaseUrl()));
    }

    private void applyR2Fields(PlatformMediaStorageSettings row, UpdateMediaStorageSettingsRequest body) {
        if (body.r2AccountId() != null) row.setR2AccountId(trimToNull(body.r2AccountId()));
        if (body.r2Endpoint() != null) row.setR2Endpoint(asEndpoint(body.r2Endpoint()));
        if (body.r2Bucket() != null) row.setR2Bucket(trimToNull(body.r2Bucket()));
        if (body.r2PublicBaseUrl() != null) row.setR2PublicBaseUrl(asBaseUrl(body.r2PublicBaseUrl()));
        if (body.r2AccessKeyId() != null) row.setR2AccessKeyIdEnc(encryptOrClear(body.r2AccessKeyId()));
        if (body.r2SecretAccessKey() != null) row.setR2SecretAccessKeyEnc(encryptOrClear(body.r2SecretAccessKey()));
    }

    private R2Connection requireConnection(PlatformMediaStorageSettings row) {
        ConnectionRead read = readConnection(row);
        if (read.connection() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Cannot switch uploads to R2: " + String.join(", ", read.problems()));
        }
        return read.connection();
    }

    private ConnectionRead readConnection(PlatformMediaStorageSettings row) {
        String endpoint = Optional.ofNullable(asEndpoint(row.getR2Endpoint()))
                .orElseGet(() -> row.getR2AccountId() != null ? R2Connection.endpointForAccount(row.getR2AccountId()) : null);
        String publicBaseUrl = asBaseUrl(row.getR2PublicBaseUrl());
        String accessKeyId = decryptOrNull(row.getR2AccessKeyIdEnc());
        String secret = decryptOrNull(row.getR2SecretAccessKeyEnc());

        List<String> problems = new ArrayList<>();
        if (endpoint == null) problems.add("account ID or endpoint is missing");
        if (row.getR2Bucket() == null) problems.add("bucket is missing");
        if (publicBaseUrl == null) problems.add("public base URL is missing");
        if (accessKeyId == null) problems.add(secretProblem("access key ID", row.getR2AccessKeyIdEnc()));
        if (secret == null) problems.add(secretProblem("secret access key", row.getR2SecretAccessKeyEnc()));
        if (!problems.isEmpty()) {
            return new ConnectionRead(null, problems);
        }
        return new ConnectionRead(
                new R2Connection(endpoint, row.getR2Bucket(), accessKeyId, secret, publicBaseUrl),
                problems);
    }

    private MediaStorageSettingsResponse toResponse(PlatformMediaStorageSettings row) {
        ConnectionRead read = readConnection(row);
        boolean secretsReadable = isReadable(row.getR2AccessKeyIdEnc()) && isReadable(row.getR2SecretAccessKeyEnc());
        return new MediaStorageSettingsResponse(
                providerOf(row).code(),
                providerOf(row) == MediaStorageProvider.R2 && read.connection() != null,
                nullToEmpty(row.getR2AccountId()),
                nullToEmpty(row.getR2Endpoint()),
                nullToEmpty(row.getR2Bucket()),
                hasValue(row.getR2AccessKeyIdEnc()),
                hasValue(row.getR2SecretAccessKeyEnc()),
                nullToEmpty(row.getR2PublicBaseUrl()),
                secretsReadable,
                secretsReadable ? null : "Stored R2 keys cannot be decrypted; re-enter them.",
                encryptionService.usesEphemeralKey(),
                row.getUpdatedAt());
    }

    private PlatformMediaStorageSettings loadOrDefault() {
        return repository.findById(PlatformMediaStorageSettings.SINGLETON_ID).orElseGet(() -> {
            PlatformMediaStorageSettings row = new PlatformMediaStorageSettings();
            row.setId(PlatformMediaStorageSettings.SINGLETON_ID);
            return row;
        });
    }

    private static MediaStorageProvider providerOf(PlatformMediaStorageSettings row) {
        return MediaStorageProvider.fromCode(row.getUploadProvider()).orElse(MediaStorageProvider.CLOUDINARY);
    }

    private String secretProblem(String label, String enc) {
        return hasValue(enc) ? label + " cannot be decrypted" : label + " is missing";
    }

    private boolean isReadable(String enc) {
        return !hasValue(enc) || decryptOrNull(enc) != null;
    }

    private String encryptOrClear(String raw) {
        String trimmed = trimToNull(raw);
        return trimmed == null ? null : encryptionService.encryptSecret(trimmed);
    }

    private String decryptOrNull(String enc) {
        if (!hasValue(enc)) {
            return null;
        }
        try {
            return trimToNull(encryptionService.decrypt(enc));
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private static boolean hasValue(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Cloudflare shows the S3 API URL with the bucket appended ({@code https://acct.r2.cloudflarestorage.com/bucket});
     * the client adds the bucket itself, so any path left on the endpoint makes every request a 404.
     * Anything that is not an R2 host (browsers autofill this optional field with an email) is
     * discarded so the endpoint is derived from the account ID instead.
     */
    private static String asEndpoint(String value) {
        String url = asBaseUrl(value);
        if (url == null) {
            return null;
        }
        int hostStart = url.indexOf(URL_SCHEME_SEPARATOR) + URL_SCHEME_SEPARATOR.length();
        int pathStart = url.indexOf('/', hostStart);
        String origin = pathStart < 0 ? url : url.substring(0, pathStart);
        String host = origin.substring(hostStart);
        boolean isR2Host = !host.contains("@") && host.endsWith(R2_HOST_SUFFIX);
        return isR2Host ? origin : null;
    }

    /** Admins often paste a bare host; the S3 client and public links both need an absolute URL. */
    private static String asBaseUrl(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return null;
        }
        String withoutSlash = trimmed.replaceAll("/+$", "");
        return withoutSlash.contains(URL_SCHEME_SEPARATOR) ? withoutSlash : DEFAULT_URL_SCHEME + withoutSlash;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record ConnectionRead(R2Connection connection, List<String> problems) {}
}
