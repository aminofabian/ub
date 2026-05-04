package zelisline.ub.exports.api;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.exports.api.dto.ExportCreateRequest;
import zelisline.ub.exports.api.dto.ExportJobResponse;
import zelisline.ub.exports.application.ExportDownload;
import zelisline.ub.exports.application.ExportService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/reports/exports")
@RequiredArgsConstructor
public class ExportsController {

    private final ExportService exportService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'reports.export')")
    @ResponseStatus(HttpStatus.CREATED)
    public ExportJobResponse create(
            @Valid @RequestBody ExportCreateRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return exportService.create(businessId, CurrentTenantUser.auditActorId(request), body, idempotencyKey);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'reports.export')")
    public ExportJobResponse metadata(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return exportService.getMetadata(businessId, id);
    }

    @GetMapping("/{id}/download")
    @PreAuthorize("hasPermission(null, 'reports.export')")
    public ResponseEntity<Resource> download(
            @PathVariable String id,
            @RequestParam("token") String token,
            HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        ExportDownload dl = exportService.openDownload(businessId, id, token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + dl.filename() + "\"")
                .contentType(dl.mediaType())
                .body(dl.resource());
    }
}
