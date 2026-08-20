package zelisline.ub.platform.logs;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Platform-side ingest config for desktop install logs.
 *
 * <p>Default S3 values are wired from the backup S3 env vars in
 * {@code application.properties} so the platform only configures storage once.
 */
@ConfigurationProperties(prefix = "app.desktop-log-ingest")
@Getter
@Setter
public class DesktopLogIngestProperties {

    /**
     * Shared key desktop installs present as {@code X-Desktop-Log-Ingest-Key}.
     * Blank disables the ingest endpoint (503).
     */
    private String key = "";

    /** When non-blank, bundles are written here instead of S3 (dev/CI). */
    private String localDir = "";

    /** S3 object key prefix (no leading slash), e.g. {@code desktop-logs}. */
    private String objectPrefix = "desktop-logs";

    /** Max accepted bundle size in bytes (also enforced by multipart limits). */
    private long maxBytes = 8L * 1024 * 1024;

    private final S3 s3 = new S3();

    @Getter
    @Setter
    public static class S3 {
        private String bucket = "";
        private String region = "eu-west-1";
        /** Optional MinIO / custom endpoint. */
        private String endpoint = "";
        private String accessKey = "";
        private String secretKey = "";
        private boolean pathStyle = false;
        private boolean defaultCredentialsChain = false;
    }
}
