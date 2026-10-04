package zelisline.ub.platform.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Cloudflare R2 {@link MediaStore}. Active when {@code app.media.r2.enabled=true}.
 *
 * <p>{@link Primary} so it wins injection while {@link CloudinaryImageService} stays up for
 * the browser-signed upload endpoints during the migration. Objects are stored at
 * {@code <folder>/<uuid>.<ext>}; that key is the persisted {@code publicId}. Cloudinary-only
 * fields ({@code phash}, colours, version) stay null, as with {@link LocalMediaStore}.
 */
@Service
@Primary
@ConditionalOnProperty(name = "app.media.r2.enabled", havingValue = "true")
public class R2MediaStore implements MediaStore, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(R2MediaStore.class);
    private static final int MAX_UPLOAD_BYTES = 12 * 1024 * 1024;
    private static final String DEFAULT_FOLDER = "ub/misc";
    private static final String R2_REGION = "auto";
    private static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";

    private final R2Properties properties;
    private final S3Client client;
    private final RemoteImageFetcher remoteFetcher;
    private final R2LegacyObjectRemover legacyRemover;

    @Autowired
    public R2MediaStore(R2Properties properties) {
        this(properties, buildClient(properties), new RemoteImageFetcher(MAX_UPLOAD_BYTES));
    }

    R2MediaStore(R2Properties properties, S3Client client, RemoteImageFetcher remoteFetcher) {
        this.properties = properties;
        this.client = client;
        this.remoteFetcher = remoteFetcher;
        this.legacyRemover = new R2LegacyObjectRemover(client, properties.getBucket());
        log.info("[R2MediaStore] active. bucket={} publicBase={}",
                properties.getBucket(), properties.resolvedPublicBaseUrl());
    }

    private static S3Client buildClient(R2Properties properties) {
        var missing = properties.missingSettings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException(
                    "APP_MEDIA_R2_ENABLED=true but these are not set: " + String.join(", ", missing));
        }
        return S3Client.builder()
                .region(Region.of(R2_REGION))
                .endpointOverride(URI.create(properties.resolvedEndpoint()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(
                        properties.getAccessKeyId().trim(), properties.getSecretAccessKey().trim())))
                .build();
    }

    @Override
    public boolean isConfigured() {
        return true;
    }

    @Override
    public CloudinaryUploadResult uploadImage(
            byte[] fileBytes, String originalFilename, String businessId, String itemId) {
        return uploadImageToFolder(fileBytes, originalFilename,
                CloudinaryImageService.folderItems(businessId, itemId), true);
    }

    @Override
    public CloudinaryUploadResult uploadImageToFolder(byte[] fileBytes, String originalFilename, String folderPath) {
        return uploadImageToFolder(fileBytes, originalFilename, folderPath, true);
    }

    @Override
    public CloudinaryUploadResult uploadImageToFolder(
            byte[] fileBytes, String originalFilename, String folderPath, boolean requestImageFingerprinting) {
        validateSize(fileBytes);
        MediaFormat format = MediaFormat.detect(fileBytes).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image format"));
        String key = normalizeFolder(folderPath) + "/" + UUID.randomUUID() + "." + format.extension();
        putObject(key, fileBytes, format);

        Integer[] size = readPixelSize(fileBytes);
        return new CloudinaryUploadResult(
                key, publicUrl(key), size[0], size[1], (long) fileBytes.length,
                format.extension(), format.contentType(), null, null, null);
    }

    @Override
    public CloudinaryUploadResult uploadFromRemoteUrl(String remoteUrl, String folderPath) {
        byte[] bytes = remoteFetcher.fetch(remoteUrl);
        return uploadImageToFolder(bytes, null, folderPath, true);
    }

    @Override
    public void destroyImage(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            legacyRemover.delete(publicId.trim());
        } catch (SdkException e) {
            log.warn("[R2MediaStore] failed to delete {}: {}", publicId, e.getMessage());
        }
    }

    @Override
    public void destroy() {
        client.close();
    }

    private void putObject(String key, byte[] bytes, MediaFormat format) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(properties.getBucket())
                .key(key)
                .contentType(format.contentType())
                .contentLength((long) bytes.length)
                .cacheControl(IMMUTABLE_CACHE_CONTROL)
                .build();
        try {
            client.putObject(request, RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not store image in R2: " + e.getMessage());
        }
    }

    private String publicUrl(String key) {
        return properties.resolvedPublicBaseUrl() + "/" + key;
    }

    private static void validateSize(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty image file");
        }
        if (fileBytes.length > MAX_UPLOAD_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Image exceeds size limit");
        }
    }

    static String normalizeFolder(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return DEFAULT_FOLDER;
        }
        String folder = Arrays.stream(folderPath.trim().split("/+"))
                .filter(segment -> !segment.isEmpty())
                .collect(Collectors.joining("/"));
        if (folder.isEmpty() || Arrays.asList(folder.split("/")).contains("..")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid media folder");
        }
        return folder;
    }

    private static Integer[] readPixelSize(byte[] bytes) {
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            return image == null ? new Integer[] {null, null} : new Integer[] {image.getWidth(), image.getHeight()};
        } catch (IOException | RuntimeException e) {
            return new Integer[] {null, null};
        }
    }
}
