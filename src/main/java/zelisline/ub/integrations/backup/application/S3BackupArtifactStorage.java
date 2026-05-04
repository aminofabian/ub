package zelisline.ub.integrations.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.DisposableBean;
import org.springframework.util.StringUtils;

import lombok.extern.slf4j.Slf4j;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.DeleteObjectsRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ObjectIdentifier;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Object;

import zelisline.ub.integrations.backup.config.BackupProperties;

/** Uploads AES-GCM payload to S3 / MinIO when {@link BackupProperties#getLocalDir()} is empty. */
@Slf4j
public class S3BackupArtifactStorage implements BackupArtifactStorage, DisposableBean {

    private final BackupProperties properties;
    private final S3Client client;

    public S3BackupArtifactStorage(BackupProperties properties) {
        this.properties = properties;
        BackupProperties.S3 s3 = properties.getS3();
        if (!StringUtils.hasText(s3.getBucket())) {
            throw new IllegalStateException("app.integrations.backup.s3.bucket is required when local-dir is blank");
        }
        String region = s3.getRegion();
        var builder = S3Client.builder().region(Region.of(region));
        if (StringUtils.hasText(s3.getEndpoint())) {
            builder.endpointOverride(java.net.URI.create(s3.getEndpoint()));
            builder.serviceConfiguration(
                    S3Configuration.builder().pathStyleAccessEnabled(s3.isPathStyle()).build());
        }
        if (s3.isDefaultCredentialsChain()) {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        } else if (StringUtils.hasText(s3.getAccessKey()) && StringUtils.hasText(s3.getSecretKey())) {
            builder.credentialsProvider(
                    StaticCredentialsProvider.create(AwsBasicCredentials.create(
                            s3.getAccessKey(), s3.getSecretKey())));
        } else {
            builder.credentialsProvider(DefaultCredentialsProvider.create());
        }
        this.client = builder.build();
    }

    @Override
    public String storeEncryptedFile(Path encryptedFile, String objectKey) throws IOException {
        BackupProperties.S3 s3 = properties.getS3();
        long len = Files.size(encryptedFile);
        PutObjectRequest req = PutObjectRequest.builder()
                .bucket(s3.getBucket())
                .key(objectKey)
                .contentLength(len)
                .build();
        client.putObject(req, RequestBody.fromFile(encryptedFile));
        return objectKey;
    }

    @Override
    public void deleteObjectsOlderThan(Instant cutoff, String prefix) {
        BackupProperties.S3 s3 = properties.getS3();
        String bucket = s3.getBucket();
        String continuationToken = null;
        do {
            var listReq = ListObjectsV2Request.builder()
                    .bucket(bucket)
                    .prefix(prefix)
                    .continuationToken(continuationToken)
                    .build();
            var listResp = client.listObjectsV2(listReq);
            continuationToken = listResp.nextContinuationToken();
            var ids = listResp.contents().stream()
                    .filter(o -> o.lastModified() != null && o.lastModified().isBefore(cutoff))
                    .map(S3Object::key)
                    .map(k -> ObjectIdentifier.builder().key(k).build())
                    .toList();
            if (!ids.isEmpty()) {
                for (int i = 0; i < ids.size(); i += 1000) {
                    int end = Math.min(i + 1000, ids.size());
                    List<ObjectIdentifier> batch = new ArrayList<>(ids.subList(i, end));
                    client.deleteObjects(DeleteObjectsRequest.builder()
                            .bucket(bucket)
                            .delete(del -> del.objects(batch))
                            .build());
                    log.info("S3 backup retention deleted {} objects under {}", batch.size(), prefix);
                }
            }
        } while (continuationToken != null && !continuationToken.isEmpty());
    }

    @Override
    public void destroy() {
        if (client != null) {
            client.close();
        }
    }
}
