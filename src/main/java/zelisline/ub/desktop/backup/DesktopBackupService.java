package zelisline.ub.desktop.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly mysqldump backup for the desktop SKU
 * (see {@code DESKTOP_INSTALLATION.md} §14).
 *
 * <p>At 23:00 every night, backs up the {@code ub} database to
 * {@code APP_DATA/backups/ub-yyyy-MM-dd.sql.gz}. Retains the last 30 days.
 */
@Service
@Profile("desktop")
public class DesktopBackupService {

    private static final Logger log = LoggerFactory.getLogger(DesktopBackupService.class);

    private final Path backupDir;
    private final Path mariadbDumpBin;
    private final String dbHost;
    private final String dbPort;
    private final String dbUser;
    private final String dbPass;
    private final int retentionDays;

    public DesktopBackupService(
            @Value("${APP_DATA:${user.home}/.palmart}") String appDataDir,
            @Value("${APP_DESKTOP_DB_PORT:33306}") String dbPort,
            @Value("${APP_DESKTOP_DB_USER:ub_local}") String dbUser,
            @Value("${APP_DESKTOP_DB_PASSWORD:}") String dbPass,
            @Value("${app.desktop.backup.retention-days:30}") int retentionDays) {
        // Backward compatibility: earlier dev builds used ~/.kiosk.
        // If APP_DATA is not explicitly set and the legacy folder exists,
        // keep writing backups there.
        String resolved = appDataDir;
        if (System.getenv("APP_DATA") == null) {
            Path primary = Path.of(appDataDir);
            Path legacy = Path.of(System.getProperty("user.home"), ".kiosk");
            if (!Files.exists(primary) && Files.exists(legacy)) {
                resolved = legacy.toString();
            }
        }

        this.backupDir = Path.of(resolved, "backups");
        this.mariadbDumpBin = findMariadbDump();
        this.dbHost = "127.0.0.1";
        this.dbPort = dbPort;
        this.dbUser = dbUser;
        this.dbPass = dbPass;
        this.retentionDays = retentionDays;
    }

    private Path findMariadbDump() {
        for (String name : List.of("mariadb-dump", "mysqldump")) {
            for (String prefix : List.of("/opt/homebrew/opt/mariadb@10.11/bin/",
                                         "/usr/local/opt/mariadb@10.11/bin/",
                                         "/usr/bin/")) {
                Path p = Path.of(prefix, name);
                if (Files.isExecutable(p)) return p;
            }
        }
        return Path.of("mariadb-dump"); // hope it's on PATH
    }

    /** Nightly at 23:00 */
    @Scheduled(cron = "0 0 23 * * ?")
    public void scheduledBackup() {
        try {
            runBackup();
            cleanupOldBackups();
        } catch (Exception e) {
            log.error("[Backup] scheduled backup failed: {}", e.getMessage());
        }
    }

    /** Run backup now (called from the "Backup now" button). Returns the filename. */
    public String backupNow() throws IOException {
        String filename = runBackup();
        cleanupOldBackups();
        return filename;
    }

    /** List existing backups with sizes and dates. */
    public List<BackupInfo> listBackups() throws IOException {
        ensureDir();
        List<BackupInfo> list = new ArrayList<>();
        try (Stream<Path> files = Files.list(backupDir)) {
            files.filter(f -> f.getFileName().toString().endsWith(".sql.gz"))
                 .forEach(f -> {
                     long size = f.toFile().length();
                     try {
                         Instant mod = Files.getLastModifiedTime(f).toInstant();
                         list.add(new BackupInfo(f.getFileName().toString(), size, mod));
                     } catch (IOException ignored) {}
                 });
        }
        list.sort((a, b) -> b.modifiedAt().compareTo(a.modifiedAt()));
        return list;
    }

    /** Restore from a backup file. DANGER: overwrites current database. */
    public void restore(String filename) throws IOException {
        Path file = backupDir.resolve(filename).normalize();
        if (!Files.exists(file) || !file.startsWith(backupDir)) {
            throw new IOException("Backup file not found: " + filename);
        }
        log.warn("[Backup] RESTORING from {} — this will overwrite the current database!", filename);
        ProcessBuilder pb = new ProcessBuilder(
            "sh", "-c",
            "gunzip -c '" + file + "' | " + mariadbDumpBin + " -h" + dbHost + " -P" + dbPort + " -u" + dbUser + " -p" + dbPass + " ub"
        );
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        log.info("[Backup] restore from {} completed with exit code {}", filename, p.exitValue());
    }

    // ── internal ──────────────────────────────────────────────────────────

    private String runBackup() throws IOException {
        ensureDir();
        String filename = "ub-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".sql.gz";
        Path dest = backupDir.resolve(filename);

        log.info("[Backup] dumping database to {}", dest);
        ProcessBuilder pb = new ProcessBuilder("sh", "-c",
            mariadbDumpBin + " -h" + dbHost + " -P" + dbPort + " -u" + dbUser + " -p" + dbPass +
            " --single-transaction --routines --triggers ub | gzip > '" + dest + "'");
        pb.redirectErrorStream(true);
        Process p = pb.start();
        try { p.waitFor(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

        long size = Files.size(dest);
        log.info("[Backup] completed: {} ({} bytes)", filename, size);
        return filename;
    }

    private void cleanupOldBackups() throws IOException {
        ensureDir();
        Instant cutoff = Instant.now().minusSeconds((long) retentionDays * 86400);
        try (Stream<Path> files = Files.list(backupDir)) {
            files.filter(f -> {
                try {
                    return Files.getLastModifiedTime(f).toInstant().isBefore(cutoff)
                        && f.getFileName().toString().endsWith(".sql.gz");
                } catch (IOException e) { return false; }
            }).forEach(f -> {
                try {
                    Files.delete(f);
                    log.info("[Backup] deleted old backup: {}", f.getFileName());
                } catch (IOException e) {
                    log.warn("[Backup] failed to delete {}: {}", f.getFileName(), e.getMessage());
                }
            });
        }
    }

    private void ensureDir() throws IOException {
        Files.createDirectories(backupDir);
    }

    public record BackupInfo(String filename, long sizeBytes, Instant modifiedAt) {}
}
