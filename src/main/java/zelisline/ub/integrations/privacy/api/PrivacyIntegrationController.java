package zelisline.ub.integrations.privacy.api;

import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.privacy.api.dto.PrivacyExportCreateRequest;
import zelisline.ub.integrations.privacy.api.dto.PrivacyExportDownload;
import zelisline.ub.integrations.privacy.api.dto.PrivacyExportJobResponse;
import zelisline.ub.integrations.privacy.application.CustomerAnonymisationService;
import zelisline.ub.integrations.privacy.application.PrivacyExportService;
import zelisline.ub.integrations.privacy.application.UserAnonymisationService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/integrations/privacy")
@RequiredArgsConstructor
@Validated
public class PrivacyIntegrationController {

    private final PrivacyExportService privacyExportService;
    private final CustomerAnonymisationService customerAnonymisationService;
    private final UserAnonymisationService userAnonymisationService;

    @PostMapping("/exports")
    @PreAuthorize("hasPermission(null, 'integrations.privacy.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PrivacyExportJobResponse createExport(
            @Valid @RequestBody PrivacyExportCreateRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return privacyExportService.create(businessId, CurrentTenantUser.auditActorId(request), body);
    }

    @GetMapping("/exports/{id}")
    @PreAuthorize("hasPermission(null, 'integrations.privacy.manage')")
    public PrivacyExportJobResponse getExport(@PathVariable("id") String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return privacyExportService.getMetadata(businessId, id);
    }

    @GetMapping("/exports/{id}/download")
    @PreAuthorize("hasPermission(null, 'integrations.privacy.manage')")
    public ResponseEntity<Resource> downloadExport(
            @PathVariable("id") String id,
            @RequestParam("token") String token,
            HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        PrivacyExportDownload dl = privacyExportService.openDownload(businessId, id, token);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + dl.filename() + "\"")
                .contentType(dl.mediaType())
                .body(dl.resource());
    }

    @PostMapping("/customers/{customerId}/anonymise")
    @PreAuthorize("hasPermission(null, 'integrations.privacy.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonymiseCustomer(@PathVariable String customerId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        customerAnonymisationService.anonymiseCustomer(businessId, customerId);
    }

    @PostMapping("/users/{userId}/anonymise")
    @PreAuthorize("hasPermission(null, 'integrations.privacy.manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void anonymiseUser(@PathVariable String userId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        userAnonymisationService.anonymiseUser(businessId, userId);
    }
}
