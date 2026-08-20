package zelisline.ub.platform.logs;

import java.io.IOException;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Ingest for Kiosk Desktop log bundles (Super Admin → Platform → Logs).
 *
 * <p>Public (see {@code SecurityConfig}) but gated by a shared
 * {@code X-Desktop-Log-Ingest-Key} so only installs that know the key can
 * upload. The payload is a gzip bundle the desktop reporter builds from its
 * log files; it is stored in S3 (or the local dir for dev) and indexed in
 * {@code desktop_log_uploads}.
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/platform/desktop-logs")
@RequiredArgsConstructor
public class DesktopLogIngestController {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneOffset.UTC);

    private final DesktopLogIngestProperties properties;
    private final DesktopLogStorage storage;
    private final DesktopLogUploadRepository repository;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> ingest(
            @RequestHeader(value = "X-Desktop-Log-Ingest-Key", required = false) String key,
            @RequestParam(value = "installId", required = false) String installId,
            @RequestParam(value = "version", required = false) String version,
            @RequestParam(value = "businessId", required = false) String businessId,
            @RequestParam(value = "log", required = false) MultipartFile logFile) throws IOException {

        if (!properties.getKey().isBlank() && !properties.getKey().equals(key)) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(java.util.Map.of("title", "Invalid ingest key"));
        }
        if (properties.getKey().isBlank() || !storage.isStorageConfigured()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(java.util.Map.of("title", "Desktop log ingest is not configured"));
        }
        if (installId == null || installId.isBlank() || installId.length() > 64) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(java.util.Map.of("title", "installId is required (max 64 chars)"));
        }
        if (logFile == null || logFile.isEmpty()) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                    .body(java.util.Map.of("title", "log bundle is required"));
        }
        if (logFile.getSize() > properties.getMaxBytes()) {
            return ResponseEntity.status(HttpStatus.PAYLOAD_TOO_LARGE)
                    .body(java.util.Map.of("title", "log bundle exceeds the size limit"));
        }

        String id = UUID.randomUUID().toString();
        String stamp = TS.format(Instant.now());
        String objectKey = "%s/%s/%s-%s.gz"
                .formatted(properties.getObjectPrefix(), sanitize(installId), stamp, id.substring(0, 8));
        String filename = "kiosk-logs-%s.gz".formatted(stamp);

        storage.store(objectKey, logFile.getBytes());

        DesktopLogUpload row = new DesktopLogUpload();
        row.setId(id);
        row.setInstallId(installId.trim());
        row.setBusinessId(blankToNull(businessId));
        row.setAppVersion(blankToNull(version));
        row.setFileKey(objectKey);
        row.setFilename(filename);
        row.setSizeBytes(logFile.getSize());
        row.setUploadedAt(Instant.now());
        repository.save(row);

        log.info("Desktop log bundle stored: installId={} size={} key={}",
                row.getInstallId(), row.getSizeBytes(), objectKey);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(java.util.Map.of(
                        "id", id,
                        "installId", row.getInstallId(),
                        "sizeBytes", row.getSizeBytes(),
                        "uploadedAt", row.getUploadedAt()));
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String blankToNull(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
