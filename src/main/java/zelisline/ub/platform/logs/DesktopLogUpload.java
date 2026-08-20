package zelisline.ub.platform.logs;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Index row for a log bundle shipped from a Kiosk Desktop install.
 *
 * <p>The gzip payload lives in object storage (or the ingest local dir for
 * dev); this table records who sent it, when, and where it is stored so the
 * Super Admin console can list and fetch bundles per install.
 */
@Entity
@Table(name = "desktop_log_uploads")
@Getter
@Setter
public class DesktopLogUpload {

    @Id
    @Column(name = "id", length = 36)
    private String id;

    /** Stable per-install id ({@code APP_DATA/conf/install-id} on the till). */
    @Column(name = "install_id", nullable = false, length = 64)
    private String installId;

    /** Single-tenant business id the install belongs to, when known. */
    @Column(name = "business_id", length = 36)
    private String businessId;

    /** Kiosk Desktop version that produced the bundle. */
    @Column(name = "app_version", length = 64)
    private String appVersion;

    /** Storage key (S3 object key or relative path under the local dir). */
    @Column(name = "file_key", nullable = false, length = 512)
    private String fileKey;

    @Column(name = "filename", nullable = false, length = 255)
    private String filename;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;
}
