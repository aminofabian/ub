package zelisline.ub.desktop.logs;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import kong.unirest.Unirest;
import zelisline.ub.UbApplication;

/**
 * Best-effort log shipping for desktop installs.
 *
 * <p>The till runs fully offline-first; this reporter only acts when the
 * machine happens to have internet. Every attempt is short-timeout,
 * fire-and-forget, and never raises — a failed or missing upload must never
 * disturb the shop. When the platform's ingest key is present (env
 * {@code APP_DESKTOP_LOG_INGEST_KEY} or {@code APP_DATA/conf/log-ingest-key}),
 * it POSTs a gzip bundle of the local log tails to the platform so support
 * can see install failures (see Super Admin → Platform → Logs).
 */
@Component
@Profile("desktop")
@ConditionalOnProperty(
        name = "app.desktop.log-reporting.enabled",
        havingValue = "true",
        matchIfMissing = true)
public class DesktopLogReporter {

    private static final Logger log = LoggerFactory.getLogger(DesktopLogReporter.class);

    /** Log files the shell/backend write into APP_DATA. */
    private static final List<String> LOG_FILES =
            List.of("kiosk.log", "backend.out.log", "backend.err.log", "mariadb.log");

    private final Path appData;
    private final String ingestUrl;
    private final int tailBytes;
    private final int connectTimeoutMs;
    private final int readTimeoutMs;
    private final String businessId;
    private final String appVersion;

    public DesktopLogReporter(
            @Value("${APP_DATA:${user.home}/.palmart}") String appDataDir,
            @Value("${app.desktop.log-reporting.ingest-url:https://kiosk.ke/api/v1/platform/desktop-logs}") String ingestUrl,
            @Value("${app.desktop.log-reporting.tail-bytes:524288}") int tailBytes,
            @Value("${app.desktop.log-reporting.connect-timeout-ms:5000}") int connectTimeoutMs,
            @Value("${app.desktop.log-reporting.read-timeout-ms:15000}") int readTimeoutMs,
            @Value("${app.desktop.business-id:}") String businessId) {
        this.appData = Path.of(appDataDir);
        this.ingestUrl = ingestUrl;
        this.tailBytes = Math.max(1024, tailBytes);
        this.connectTimeoutMs = connectTimeoutMs;
        this.readTimeoutMs = readTimeoutMs;
        this.businessId = businessId;
        String v = UbApplication.class.getPackage().getImplementationVersion();
        this.appVersion = (v == null || v.isBlank()) ? "unknown" : v;
    }

    @Scheduled(
            fixedDelayString = "${app.desktop.log-reporting.interval-ms:21600000}",
            initialDelayString = "${app.desktop.log-reporting.initial-delay-ms:120000}")
    public void scheduledReport() {
        reportOnce();
    }

    void reportOnce() {
        try {
            String key = resolveIngestKey();
            if (key.isBlank()) {
                log.debug("Desktop log reporting disabled — no ingest key configured.");
                return;
            }
            String installId = resolveInstallId();
            byte[] bundle = buildBundle();
            if (bundle.length == 0) {
                log.debug("Desktop log reporting skipped — no log files present.");
                return;
            }
            var resp = Unirest.post(ingestUrl)
                    .header("X-Desktop-Log-Ingest-Key", key)
                    .connectTimeout(connectTimeoutMs)
                    .socketTimeout(readTimeoutMs)
                    .field("installId", installId)
                    .field("version", appVersion)
                    .field("businessId", businessId)
                    .field("log", bundle, "logs.gz")
                    .asString();
            if (resp.isSuccess()) {
                log.info("Desktop log bundle sent to {} ({} bytes, HTTP {})",
                        ingestUrl, bundle.length, resp.getStatus());
            } else {
                log.warn("Desktop log bundle rejected by {} (HTTP {}: {})",
                        ingestUrl, resp.getStatus(), resp.getBody() == null ? "" : resp.getBody());
            }
        } catch (Exception e) {
            log.warn("Desktop log reporting failed (offline?): {}", e.getMessage());
        }
    }

    /** Env var first, then a per-install key file the operator can drop in. */
    private String resolveIngestKey() {
        String env = System.getenv("APP_DESKTOP_LOG_INGEST_KEY");
        if (env != null && !env.isBlank()) {
            return env.trim();
        }
        Path keyFile = appData.resolve("conf").resolve("log-ingest-key");
        if (Files.isReadable(keyFile)) {
            try {
                return Files.readString(keyFile).trim();
            } catch (IOException e) {
                log.debug("Could not read log-ingest-key file: {}", e.getMessage());
            }
        }
        return "";
    }

    /** Stable per-install id: read {@code APP_DATA/conf/install-id} or create it. */
    private String resolveInstallId() throws IOException {
        Path conf = appData.resolve("conf");
        Path file = conf.resolve("install-id");
        if (Files.isReadable(file)) {
            String existing = Files.readString(file).trim();
            if (!existing.isBlank()) {
                return existing;
            }
        }
        Files.createDirectories(conf);
        String id = UUID.randomUUID().toString();
        Files.writeString(file, id, StandardCharsets.UTF_8);
        return id;
    }

    /** gzip of a text envelope: {@code ===== <file> =====} + tail for each log. */
    private byte[] buildBundle() throws IOException {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        boolean includedAny = false;
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            for (String name : LOG_FILES) {
                Path path = appData.resolve(name);
                if (!Files.isReadable(path)) {
                    continue;
                }
                String tail = readTail(path, tailBytes);
                gz.write(("===== " + name + " =====\n").getBytes(StandardCharsets.UTF_8));
                gz.write(tail.getBytes(StandardCharsets.UTF_8));
                if (!tail.endsWith("\n")) {
                    gz.write('\n');
                }
                includedAny = true;
            }
        }
        return includedAny ? out.toByteArray() : new byte[0];
    }

    private static String readTail(Path path, int maxBytes) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxBytes);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            return new String(buf, StandardCharsets.UTF_8);
        }
    }
}
