package zelisline.ub.integrations.backup.application;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import javax.sql.DataSource;

import com.zaxxer.hikari.HikariDataSource;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import zelisline.ub.integrations.backup.config.BackupProperties;
import zelisline.ub.integrations.backup.domain.BackupRun;
import zelisline.ub.integrations.backup.repository.BackupRunRepository;

/**
 * Nightly / on-demand encrypted DB artefact pipeline: native dump → AES-GCM → S3 or local dir.
 */
@RequiredArgsConstructor
@Slf4j
public class DatabaseBackupOrchestrator {

    private final BackupProperties properties;
    private final BackupRunRepository backupRunRepository;
    private final ExternalProcessDatabaseDumper dumper;
    private final BackupEncryptionService encryption;
    private final BackupArtifactStorage artifactStorage;
    private final DataSource dataSource;
    private final MeterRegistry meterRegistry;

    private final AtomicLong lastSuccessEpochSeconds = new AtomicLong(0);

    @jakarta.annotation.PostConstruct
    void registerGauge() {
        Gauge.builder("backup.last.success.epoch.seconds", lastSuccessEpochSeconds, a -> (double) a.get())
                .description("Unix epoch seconds of last successful DB backup (0 = never)")
                .register(meterRegistry);
    }

    public void runBackup() {
        if (!(dataSource instanceof HikariDataSource h)) {
            failFast("DataSource is not Hikari; cannot run backup");
            return;
        }
        if (!encryption.isReady()) {
            failFast("app.integrations.backup.encryption.passphrase is not set");
            return;
        }

        String jdbcUrl = h.getJdbcUrl();
        JdbcDumpTarget target;
        try {
            target = JdbcDumpTarget.fromJdbcUrl(jdbcUrl);
        } catch (Exception ex) {
            persistUnsupported(ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName());
            log.error("Backup skipped: unsupported JDBC URL", ex);
            return;
        }

        String id = UUID.randomUUID().toString();
        BackupRun run = new BackupRun();
        run.setId(id);
        run.setStatus(BackupRun.Status.running);
        run.setEngine(target.engine());
        run.setStartedAt(Instant.now());
        backupRunRepository.save(run);

        Path tempDir = null;
        Path plainPath = null;
        Path encPath = null;
        try {
            tempDir = Files.createTempDirectory("ub-db-backup-");
            if (target.engine() == BackupRun.Engine.postgres) {
                plainPath = tempDir.resolve("dump.pgdump");
            } else {
                plainPath = tempDir.resolve("dump.sql");
            }
            encPath = tempDir.resolve("dump.enc");

            dumper.dumpToFile(plainPath);
            long plainSize = Files.size(plainPath);
            encryption.encryptFile(plainPath, encPath);
            long encSize = Files.size(encPath);
            String sha = sha256Hex(encPath);

            String objectKey = objectKeyFor(id);
            artifactStorage.storeEncryptedFile(encPath, objectKey);

            run.setStatus(BackupRun.Status.completed);
            run.setStorageKey(objectKey);
            run.setPlaintextBytes(plainSize);
            run.setEncryptedBytes(encSize);
            run.setSha256Hex(sha);
            run.setFinishedAt(Instant.now());
            backupRunRepository.save(run);

            lastSuccessEpochSeconds.set(Instant.now().getEpochSecond());
            log.info("DB backup completed id={} key={} encBytes={}", id, objectKey, encSize);

            applyRetention();
        } catch (Exception ex) {
            log.error("DB backup failed id={}", id, ex);
            run.setStatus(BackupRun.Status.failed);
            run.setErrorMessage(truncate(ex.getMessage(), 2000));
            run.setFinishedAt(Instant.now());
            backupRunRepository.save(run);
        } finally {
            try {
                if (plainPath != null) {
                    Files.deleteIfExists(plainPath);
                }
                if (encPath != null) {
                    Files.deleteIfExists(encPath);
                }
                if (tempDir != null) {
                    try (var walk = Files.walk(tempDir)) {
                        walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                            try {
                                Files.deleteIfExists(p);
                            } catch (IOException ignored) {
                                // best-effort
                            }
                        });
                    }
                }
            } catch (IOException e) {
                log.warn("Cleanup temp backup files failed", e);
            }
        }
    }

    private void applyRetention() {
        int days = properties.getRetentionDays();
        if (days <= 0) {
            return;
        }
        Instant cutoff = Instant.now().minus(days, ChronoUnit.DAYS);
        String prefix = properties.getObjectPrefix() + "/";
        try {
            artifactStorage.deleteObjectsOlderThan(cutoff, prefix);
        } catch (RuntimeException ex) {
            log.warn("Backup retention pass failed", ex);
        }
    }

    private String objectKeyFor(String runId) {
        String ts = Instant.now().toString().replace(":", "-");
        return properties.getObjectPrefix() + "/ub-" + ts + "-" + runId.substring(0, 8) + ".enc";
    }

    private static String sha256Hex(Path file) throws IOException {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file));
            return HexFormat.of().formatHex(hash);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void persistUnsupported(String message) {
        String id = UUID.randomUUID().toString();
        BackupRun run = new BackupRun();
        run.setId(id);
        run.setStatus(BackupRun.Status.failed);
        run.setEngine(BackupRun.Engine.unsupported);
        run.setErrorMessage(truncate(message, 2000));
        run.setStartedAt(Instant.now());
        run.setFinishedAt(Instant.now());
        backupRunRepository.save(run);
    }

    private void failFast(String message) {
        log.error("{}", message);
        String id = UUID.randomUUID().toString();
        BackupRun run = new BackupRun();
        run.setId(id);
        run.setStatus(BackupRun.Status.failed);
        run.setEngine(BackupRun.Engine.unsupported);
        run.setErrorMessage(truncate(message, 2000));
        run.setStartedAt(Instant.now());
        run.setFinishedAt(Instant.now());
        backupRunRepository.save(run);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
