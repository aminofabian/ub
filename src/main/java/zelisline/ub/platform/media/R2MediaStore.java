package zelisline.ub.platform.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * Cloudflare R2 {@link MediaStore}, built from the super-admin's saved {@link R2Connection}
 * by {@link RoutingMediaStore}. Objects are stored at {@code <folder>/<uuid>.<ext>}; that key
 * is the persisted {@code publicId}. Cloudinary-only fields ({@code phash}, colours, version)
 * stay null, as with {@link LocalMediaStore}.
 */
public class R2MediaStore implements MediaStore, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(R2MediaStore.class);
    private static final int MAX_UPLOAD_BYTES = 12 * 1024 * 1024;
    private static final int MAX_ATTACHMENT_BYTES = 15 * 1024 * 1024;
    private static final String DEFAULT_FOLDER = "ub/misc";
    private static final String R2_REGION = "auto";
    private static final String IMMUTABLE_CACHE_CONTROL = "public, max-age=31536000, immutable";
    private static final String PROBE_FOLDER = "_palmart-healthcheck";
    private static final String PROBE_CONTENT_TYPE = "text/plain";

    private final R2Connection connection;
    private final S3Client client;
    private final RemoteImageFetcher remoteFetcher;
    private final R2LegacyObjectRemover legacyRemover;

    R2MediaStore(R2Connection connection, S3Client client, RemoteImageFetcher remoteFetcher) {
        this.connection = connection;
        this.client = client;
        this.remoteFetcher = remoteFetcher;
        this.legacyRemover = new R2LegacyObjectRemover(client, connection.bucket());
    }

    public static R2MediaStore open(R2Connection connection) {
        S3Client client = S3Client.builder()
                .region(Region.of(R2_REGION))
                .endpointOverride(URI.create(connection.endpoint()))
                .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(connection.accessKeyId(), connection.secretAccessKey())))
                .build();
        log.info("[R2MediaStore] opened bucket={} publicBase={}", connection.bucket(), connection.publicBaseUrl());
        return new R2MediaStore(connection, client, new RemoteImageFetcher(MAX_UPLOAD_BYTES));
    }

    /** Writes then deletes a probe object; throws 400 with the provider's reason when the bucket is unusable. */
    public void verifyWritable() {
        String key = PROBE_FOLDER + "/" + UUID.randomUUID() + ".txt";
        byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
        try {
            client.putObject(PutObjectRequest.builder()
                    .bucket(connection.bucket()).key(key).contentType(PROBE_CONTENT_TYPE).build(),
                    RequestBody.fromBytes(body));
            client.deleteObject(DeleteObjectRequest.builder().bucket(connection.bucket()).key(key).build());
        } catch (S3Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, probeFailureMessage(e));
        } catch (SdkException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "R2 bucket is not writable: " + e.getMessage());
        }
    }

    private String probeFailureMessage(S3Exception e) {
        if (e.statusCode() == HttpStatus.NOT_FOUND.value()) {
            return "R2 bucket \"" + connection.bucket() + "\" was not found at " + connection.endpoint()
                    + ". Check the bucket name, and that the endpoint is just https://<account-id>.r2.cloudflarestorage.com";
        }
        return "R2 bucket is not writable: " + e.getMessage();
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
        putObject(key, fileBytes, format.contentType(), null);

        Integer[] size = readPixelSize(fileBytes);
        return new CloudinaryUploadResult(
                key, publicUrl(key), size[0], size[1], (long) fileBytes.length,
                format.extension(), format.contentType(), null, null, null);
    }

    /** Support attachment: stored under {@code <folder>/<uuid>.<ext>}; non-images download rather than render. */
    public CloudinaryUploadResult uploadAttachment(byte[] fileBytes, String originalFilename, String folderPath) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Empty attachment");
        }
        if (fileBytes.length > MAX_ATTACHMENT_BYTES) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "Attachment exceeds size limit");
        }
        AttachmentFormat format = AttachmentFormat.detect(fileBytes, originalFilename).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attachment type is not allowed"));
        String key = normalizeFolder(folderPath) + "/" + UUID.randomUUID() + "." + format.extension();
        String disposition = format.isImage() || format == AttachmentFormat.PDF
                ? null
                : AttachmentDisposition.download(originalFilename, format.extension());
        putObject(key, fileBytes, format.contentType(), disposition);

        Integer[] size = format.isImage() ? readPixelSize(fileBytes) : new Integer[] {null, null};
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
    public void close() {
        client.close();
    }

    private void putObject(String key, byte[] bytes, String contentType, String contentDisposition) {
        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(connection.bucket())
                .key(key)
                .contentType(contentType)
                .contentDisposition(contentDisposition)
                .contentLength((long) bytes.length)
                .cacheControl(IMMUTABLE_CACHE_CONTROL)
                .build();
        try {
            client.putObject(request, RequestBody.fromBytes(bytes));
        } catch (SdkException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not store file in R2: " + e.getMessage());
        }
    }

    private String publicUrl(String key) {
        return connection.publicBaseUrl() + "/" + key;
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
