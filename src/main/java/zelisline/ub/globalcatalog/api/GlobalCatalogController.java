package zelisline.ub.globalcatalog.api;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
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
import zelisline.ub.globalcatalog.api.dto.AdoptRequest;
import zelisline.ub.globalcatalog.api.dto.AdoptResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogJobDtos.CreateJobResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogJobDtos.JobResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMetaResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalProductPackDetailResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalProductResponse;
import zelisline.ub.globalcatalog.api.dto.PreviewAdoptRequest;
import zelisline.ub.globalcatalog.api.dto.RefreshCatalogRequest;
import zelisline.ub.globalcatalog.api.dto.RefreshCatalogResponse;
import zelisline.ub.globalcatalog.api.dto.ReplaceCatalogEligibilityResponse;
import zelisline.ub.globalcatalog.api.dto.ReplaceCatalogRequest;
import zelisline.ub.globalcatalog.api.dto.ReplaceCatalogResponse;
import zelisline.ub.globalcatalog.application.GlobalCatalogJobService;
import zelisline.ub.globalcatalog.application.GlobalCatalogRefreshService;
import zelisline.ub.globalcatalog.application.GlobalCatalogReplaceService;
import zelisline.ub.globalcatalog.application.GlobalCatalogService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/global-catalog")
@RequiredArgsConstructor
public class GlobalCatalogController {

    private final GlobalCatalogService globalCatalogService;
    private final GlobalCatalogReplaceService globalCatalogReplaceService;
    private final GlobalCatalogJobService globalCatalogJobService;
    private final GlobalCatalogRefreshService globalCatalogRefreshService;

    @GetMapping("/meta")
    @PreAuthorize("hasPermission(null, 'catalog.global.read')")
    @ResponseStatus(HttpStatus.OK)
    public GlobalCatalogMetaResponse getMeta(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return globalCatalogService.getCatalogMeta(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/products")
    @PreAuthorize("hasPermission(null, 'catalog.global.read')")
    @ResponseStatus(HttpStatus.OK)
    public Page<GlobalProductResponse> listProducts(
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false, defaultValue = "true") boolean onlyNotImported,
            Pageable pageable,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return globalCatalogService.listProducts(
                TenantRequestIds.resolveBusinessId(request),
                categoryId,
                q,
                barcode,
                onlyNotImported,
                pageable
        );
    }

    @GetMapping("/products/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.global.read')")
    @ResponseStatus(HttpStatus.OK)
    public GlobalProductResponse getProduct(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return globalCatalogService.getProduct(TenantRequestIds.resolveBusinessId(request), id);
    }

    @GetMapping("/lookup")
    @PreAuthorize("hasPermission(null, 'catalog.global.read')")
    @ResponseStatus(HttpStatus.OK)
    public List<GlobalProductResponse> lookup(
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false) String q,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return globalCatalogService.lookup(TenantRequestIds.resolveBusinessId(request), barcode, q);
    }

    @GetMapping("/packs/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.global.read')")
    @ResponseStatus(HttpStatus.OK)
    public GlobalProductPackDetailResponse getPack(
            @PathVariable String id,
            @RequestParam(required = false, defaultValue = "true") boolean onlyNotImported,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return globalCatalogService.getPack(
                TenantRequestIds.resolveBusinessId(request),
                id,
                onlyNotImported
        );
    }

    @PostMapping("/adopt/preview")
    @PreAuthorize("hasPermission(null, 'catalog.global.adopt')")
    @ResponseStatus(HttpStatus.OK)
    public AdoptResponse previewAdopt(
            @Valid @RequestBody PreviewAdoptRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return globalCatalogService.previewAdopt(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PostMapping("/adopt")
    @PreAuthorize("hasPermission(null, 'catalog.global.adopt')")
    @ResponseStatus(HttpStatus.CREATED)
    public AdoptResponse adopt(
            @Valid @RequestBody AdoptRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        GlobalCatalogJobService.requireSyncAdoptSize(body);
        String actorUserId = CurrentTenantUser.auditActorId(request);
        return globalCatalogService.adopt(
                TenantRequestIds.resolveBusinessId(request),
                body,
                actorUserId
        );
    }

    @PostMapping("/adopt/jobs")
    @PreAuthorize("hasPermission(null, 'catalog.global.adopt')")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public CreateJobResponse enqueueAdopt(
            @Valid @RequestBody AdoptRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String actorUserId = CurrentTenantUser.auditActorId(request);
        return globalCatalogJobService.enqueueAdopt(
                TenantRequestIds.resolveBusinessId(request),
                actorUserId,
                body
        );
    }

    @GetMapping("/adopt/jobs/{jobId}")
    @PreAuthorize("hasPermission(null, 'catalog.global.adopt')")
    @ResponseStatus(HttpStatus.OK)
    public JobResponse getAdoptJob(
            @PathVariable String jobId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return globalCatalogJobService.getAdoptJob(
                jobId,
                TenantRequestIds.resolveBusinessId(request)
        );
    }

    @PostMapping("/refresh/preview")
    @PreAuthorize("hasPermission(null, 'catalog.global.adopt')")
    @ResponseStatus(HttpStatus.OK)
    public RefreshCatalogResponse previewRefresh(
            @Valid @RequestBody RefreshCatalogRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return globalCatalogRefreshService.preview(
                TenantRequestIds.resolveBusinessId(request),
                body
        );
    }

    @PostMapping("/refresh")
    @PreAuthorize("hasPermission(null, 'catalog.global.adopt')")
    @ResponseStatus(HttpStatus.OK)
    public RefreshCatalogResponse refresh(
            @Valid @RequestBody RefreshCatalogRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String actorUserId = CurrentTenantUser.auditActorId(request);
        return globalCatalogRefreshService.refresh(
                TenantRequestIds.resolveBusinessId(request),
                body,
                actorUserId
        );
    }

    @GetMapping("/replace/preview")
    @PreAuthorize("hasPermission(null, 'catalog.global.adopt')")
    @ResponseStatus(HttpStatus.OK)
    public ReplaceCatalogEligibilityResponse previewReplace(
            @RequestParam String packId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return globalCatalogReplaceService.preview(
                TenantRequestIds.resolveBusinessId(request),
                packId
        );
    }

    @PostMapping("/replace")
    @PreAuthorize("hasPermission(null, 'catalog.global.adopt')")
    @ResponseStatus(HttpStatus.OK)
    public ReplaceCatalogResponse replaceCatalog(
            @Valid @RequestBody ReplaceCatalogRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String actorUserId = CurrentTenantUser.auditActorId(request);
        return globalCatalogReplaceService.replace(
                TenantRequestIds.resolveBusinessId(request),
                body,
                actorUserId
        );
    }
}
