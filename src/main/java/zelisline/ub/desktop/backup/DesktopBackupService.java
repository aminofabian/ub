package zelisline.ub.desktop.backup;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Nightly MariaDB backup for the desktop SKU.
 *
 * <p>At 23:00 every night, backs up the {@code ub} database to
 * {@code APP_DATA/backups/ub-yyyy-MM-dd.sql.gz}. Retains the last 30 days.
 *
 * <p>The dump and restore run as direct child processes (no {@code sh -c}) with
 * the password passed via the {@code MYSQL_PWD} environment variable rather
 * than on the command line, so it cannot leak through the process list and the
 * filename cannot be used for shell injection. Both paths check the child's
 * exit code, and a backup is gunzipped back before it is accepted so a
 * truncated/failed dump is never reported as a success.
 */
@Service
@Profile("desktop")
public class DesktopBackupService {

    private static final Logger log = LoggerFactory.getLogger(DesktopBackupService.class);

    private final Path backupDir;
    private final Path mariadbDumpBin;
    private final Path mariadbClientBin;
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
        this.mariadbDumpBin = findTool(List.of("mariadb-dump", "mysqldump"));
        this.mariadbClientBin = findTool(List.of("mariadb", "mysql"));
        this.dbHost = "127.0.0.1";
        this.dbPort = dbPort;
        this.dbUser = dbUser;
        this.dbPass = dbPass;
        this.retentionDays = retentionDays;
    }

    /**
     * Locate a MariaDB tool. Honours {@code APP_MARIADB_BIN_DIR} first (the
     * bundled install directory, when the shell wires it through), then the
     * common Homebrew/system locations, then falls back to PATH.
     */
    private Path findTool(List<String> names) {
        List<String> dirs = new ArrayList<>();
        String override = System.getenv("APP_MARIADB_BIN_DIR");
        if (override != null && !override.isBlank()) {
            dirs.add(override);
        }
        dirs.add("/opt/homebrew/opt/mariadb@10.11/bin/");
        dirs.add("/usr/local/opt/mariadb@10.11/bin/");
        dirs.add("/usr/bin/");
        for (String name : names) {
            for (String dir : dirs) {
                Path p = Path.of(dir, name);
                if (Files.isExecutable(p)) {
                    return p;
                }
            }
        }
        return Path.of(names.get(0)); // hope it's on PATH
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
        if (!file.startsWith(backupDir) || !Files.exists(file)) {
            throw new IOException("Backup file not found: " + filename);
        }
        log.warn("[Backup] RESTORING from {} — this will overwrite the current database!", filename);

        Path errFile = backupDir.resolve(filename + ".restore.err");
        List<String> cmd = List.of(
            mariadbClientBin.toString(),
            "-h", dbHost, "-P", dbPort, "-u", dbUser, "ub"
        );
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("MYSQL_PWD", dbPass == null ? "" : dbPass);
        pb.redirectError(ProcessBuilder.Redirect.to(errFile.toFile()));

        Process p = pb.start();
        try (GZIPInputStream gz = new GZIPInputStream(Files.newInputStream(file));
             OutputStream stdin = p.getOutputStream()) {
            gz.transferTo(stdin);
        } catch (IOException e) {
            p.destroyForcibly();
            Files.deleteIfExists(errFile);
            throw new IOException("restore stream failed: " + e.getMessage(), e);
        }

        int exit = waitFor(p);
        String detail = readAll(errFile);
        Files.deleteIfExists(errFile);
        if (exit != 0) {
            throw new IOException("restore failed (exit " + exit + "): " + detail);
        }
        log.info("[Backup] restore from {} completed.", filename);
    }

    // ── internal ──────────────────────────────────────────────────────────

    private String runBackup() throws IOException {
        ensureDir();
        String filename = "ub-" + LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE) + ".sql.gz";
        Path dest = backupDir.resolve(filename);
        Path tmp = backupDir.resolve(filename + ".part");
        Path errFile = backupDir.resolve(filename + ".err");

        log.info("[Backup] dumping database to {}", dest);
        // Password goes through MYSQL_PWD (env), never argv, so it is not
        // visible via `ps` / Task Manager.
        List<String> cmd = List.of(
            mariadbDumpBin.toString(),
            "-h", dbHost, "-P", dbPort, "-u", dbUser,
            "--single-transaction", "--routines", "--triggers", "ub"
        );
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.environment().put("MYSQL_PWD", dbPass == null ? "" : dbPass);
        pb.redirectError(ProcessBuilder.Redirect.to(errFile.toFile()));

        Process p = pb.start();
        try (InputStream in = p.getInputStream();
             OutputStream raw = Files.newOutputStream(tmp,
                 StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
             GZIPOutputStream gz = new GZIPOutputStream(raw)) {
            in.transferTo(gz);
        } catch (IOException e) {
            p.destroyForcibly();
            Files.deleteIfExists(tmp);
            Files.deleteIfExists(errFile);
            throw new IOException("backup stream failed: " + e.getMessage(), e);
        }

        int exit = waitFor(p);
        if (exit != 0) {
            Files.deleteIfExists(tmp);
            throw new IOException("mariadb-dump exited " + exit + ": " + readAll(errFile));
        }
        // Verify before accepting: a truncated or empty dump must never be
        // reported as a successful backup.
        long sqlBytes;
        try {
            sqlBytes = countDecompressedBytes(tmp);
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw new IOException("backup verification failed (corrupt gzip): " + e.getMessage(), e);
        }
        if (sqlBytes <= 0) {
            Files.deleteIfExists(tmp);
            throw new IOException("backup verification failed: empty dump");
        }
        Files.move(tmp, dest, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        Files.deleteIfExists(errFile);

        log.info("[Backup] completed: {} ({} bytes gz, {} bytes sql)",
            filename, Files.size(dest), sqlBytes);
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

    private static int waitFor(Process p) throws IOException {
        try {
            return p.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            p.destroyForcibly();
            throw new IOException("backup/restore interrupted");
        }
    }

    /** Fully decompress to prove the gzip is intact; returns the byte count. */
    private static long countDecompressedBytes(Path gz) throws IOException {
        try (GZIPInputStream in = new GZIPInputStream(Files.newInputStream(gz))) {
            byte[] buf = new byte[1 << 16];
            long total = 0;
            int n;
            while ((n = in.read(buf)) != -1) {
                total += n;
            }
            return total;
        }
    }

    private static String readAll(Path file) {
        try {
            if (!Files.exists(file)) {
                return "";
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            return text.length() > 2000 ? text.substring(0, 2000) + "…" : text;
        } catch (IOException e) {
            return "";
        }
    }

    public record BackupInfo(String filename, long sizeBytes, Instant modifiedAt) {}
}
