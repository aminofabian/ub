package zelisline.ub.platform.logs;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;

import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;

/**
 * Super Admin view over desktop install log bundles. Secured by
 * {@code /api/v1/super-admin/**} → {@code ROLE_SUPER_ADMIN}.
 */
@RestController
@RequestMapping("/api/v1/super-admin/platform/desktop-logs")
@RequiredArgsConstructor
public class SuperAdminDesktopLogsController {

    private final DesktopLogUploadRepository repository;
    private final DesktopLogStorage storage;

    public record DesktopLogRow(
            String id,
            String installId,
            String businessId,
            String appVersion,
            String filename,
            long sizeBytes,
            Instant uploadedAt) {
    }

    @GetMapping
    public List<DesktopLogRow> list(
            @RequestParam(value = "installId", required = false) String installId,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        int capped = Math.max(1, Math.min(limit, 200));
        List<DesktopLogUpload> rows = (installId == null || installId.isBlank())
                ? repository.findTop50ByOrderByUploadedAtDesc()
                : repository.findTop50ByInstallIdOrderByUploadedAtDesc(installId.trim());
        return rows.stream().limit(capped).map(this::toRow).toList();
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable("id") String id) throws IOException {
        DesktopLogUpload row = repository.findById(id).orElseThrow(
                () -> new ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND));
        InputStream in = storage.open(row.getFileKey());
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + row.getFilename() + "\"")
                .body(new InputStreamResource(in));
    }

    private DesktopLogRow toRow(DesktopLogUpload row) {
        return new DesktopLogRow(
                row.getId(),
                row.getInstallId(),
                row.getBusinessId(),
                row.getAppVersion(),
                row.getFilename(),
                row.getSizeBytes(),
                row.getUploadedAt());
    }
}
