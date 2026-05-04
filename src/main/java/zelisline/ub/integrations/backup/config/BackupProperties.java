package zelisline.ub.integrations.backup.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Phase 8 Slice 4 — encrypted DB dumps to local dir (dev) or S3-compatible storage (prod).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "app.integrations.backup")
public class BackupProperties {

    /**
     * Master switch for the orchestrator bean + optional S3 client wiring.
     * Scheduler is separately gated by {@link #scheduler}.{@link Scheduler#isEnabled() enabled}.
     */
    private boolean enabled = false;

    /**
     * When non-blank, encrypted artefacts are written here instead of S3 (CI/dev). Example:
     * {@code /var/lib/ub/backups} or {@code ${java.io.tmpdir}/ub-backups}.
     */
    private String localDir = "";

    /** S3 key prefix, no leading slash (e.g. {@code db-backups}). */
    private String objectPrefix = "db-backups";

    /** Delete older objects in {@code objectPrefix} after a successful upload (S3/local listing). */
    private int retentionDays = 30;

    private final Encryption encryption = new Encryption();
    private final Scheduler scheduler = new Scheduler();
    private final S3 s3 = new S3();

    @Getter
    @Setter
    public static class Encryption {
        /** Passphrase for PBKDF2 + AES-GCM. Required to run a backup. */
        private String passphrase = "";

        private int pbkdf2Iterations = 210_000;
    }

    @Getter
    @Setter
    public static class Scheduler {
        private boolean enabled = false;
        /** Spring 6-field cron ({@code sec min hour day month weekday}). */
        private String cron = "0 0 3 * * *";
        private String zone = "UTC";
    }

    @Getter
    @Setter
    public static class S3 {
        private String bucket = "";
        private String region = "eu-west-1";
        /** Optional MinIO / custom endpoint, e.g. {@code http://127.0.0.1:9000}. */
        private String endpoint = "";
        private String accessKey = "";
        private String secretKey = "";
        private boolean pathStyle = false;
        /**
         * When true and access/secret are blank, use the AWS SDK default credentials provider
         * (instance role, env, profile).
         */
        private boolean defaultCredentialsChain = false;
    }
}
