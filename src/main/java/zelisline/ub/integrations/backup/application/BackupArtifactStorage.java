package zelisline.ub.integrations.backup.application;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;

/** Persists encrypted backup payloads (local folder or S3-compatible). */
public interface BackupArtifactStorage {

    /**
     * @param objectKey full key within the bucket or under {@link zelisline.ub.integrations.backup.config.BackupProperties#getLocalDir()}
     */
    String storeEncryptedFile(Path encryptedFile, String objectKey) throws IOException;

    /**
     * Removes objects whose last-modified is strictly before {@code cutoff}.
     *
     * @param prefixFilter key prefix (e.g. {@code db-backups/})
     */
    void deleteObjectsOlderThan(Instant cutoff, String prefixFilter);
}
