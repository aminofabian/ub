package zelisline.ub.platform.logs;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * Stores gzip log bundles shipped by desktop installs — local dir when
 * {@code app.desktop-log-ingest.local-dir} is set (dev/CI), otherwise S3.
 */
@Slf4j
@Service
public class DesktopLogStorage implements DisposableBean {

    private final DesktopLogIngestProperties properties;
    private S3Client s3;

    public DesktopLogStorage(DesktopLogIngestProperties properties) {
        this.properties = properties;
    }

    /** @return the full storage key used, for the index row. */
    public String store(String objectKey, byte[] payload) throws IOException {
        if (StringUtils.hasText(properties.getLocalDir())) {
            Path target = Path.of(properties.getLocalDir()).resolve(objectKey);
            Files.createDirectories(target.getParent());
            Files.write(target, payload);
            return objectKey;
        }
        S3Client client = s3Client();
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(objectKey)
                .contentLength((long) payload.length)
                .build();
        client.putObject(req, RequestBody.fromBytes(payload));
        return objectKey;
    }

    public InputStream open(String objectKey) throws IOException {
        if (StringUtils.hasText(properties.getLocalDir())) {
            return Files.newInputStream(Path.of(properties.getLocalDir()).resolve(objectKey));
        }
        S3Client client = s3Client();
        GetObjectRequest req = GetObjectRequest.builder()
                .bucket(properties.getS3().getBucket())
                .key(objectKey)
                .build();
        return client.getObject(req);
    }

    /** True when this deployment can actually receive bundles (storage configured). */
    public boolean isStorageConfigured() {
        return StringUtils.hasText(properties.getLocalDir())
                || StringUtils.hasText(properties.getS3().getBucket());
    }

    private synchronized S3Client s3Client() {
        if (s3 == null) {
            DesktopLogIngestProperties.S3 s3p = properties.getS3();
            if (!StringUtils.hasText(s3p.getBucket())) {
                throw new IllegalStateException(
                        "app.desktop-log-ingest.s3.bucket is blank and local-dir is unset");
            }
            var builder = S3Client.builder().region(Region.of(s3p.getRegion()));
            if (StringUtils.hasText(s3p.getEndpoint())) {
                builder.endpointOverride(java.net.URI.create(s3p.getEndpoint()));
                builder.serviceConfiguration(
                        S3Configuration.builder().pathStyleAccessEnabled(s3p.isPathStyle()).build());
            }
            if (s3p.isDefaultCredentialsChain()) {
                builder.credentialsProvider(DefaultCredentialsProvider.create());
            } else if (StringUtils.hasText(s3p.getAccessKey()) && StringUtils.hasText(s3p.getSecretKey())) {
                builder.credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(s3p.getAccessKey(), s3p.getSecretKey())));
            } else {
                builder.credentialsProvider(DefaultCredentialsProvider.create());
            }
            s3 = builder.build();
            log.info("Desktop log storage: S3 bucket={} region={}", s3p.getBucket(), s3p.getRegion());
        }
        return s3;
    }

    @Override
    public void destroy() {
        if (s3 != null) {
            s3.close();
            s3 = null;
        }
    }
}
