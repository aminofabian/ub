package zelisline.ub.catalog.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.AisleResponse;
import zelisline.ub.catalog.api.dto.AisleUnassignedCountResponse;
import zelisline.ub.catalog.api.dto.CreateAisleRequest;
import zelisline.ub.catalog.api.dto.PatchAisleRequest;
import zelisline.ub.catalog.application.CatalogTaxonomyService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/aisles")
@RequiredArgsConstructor
public class AislesController {

    private final CatalogTaxonomyService catalogTaxonomyService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<AisleResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.listAisles(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/unassigned-count")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public AisleUnassignedCountResponse unassignedCount(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return new AisleUnassignedCountResponse(catalogTaxonomyService.countUnassignedAisleProducts(businessId));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public AisleResponse create(@Valid @RequestBody CreateAisleRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.createAisle(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    public AisleResponse patch(
            @PathVariable String id,
            @Valid @RequestBody PatchAisleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.updateAisle(TenantRequestIds.resolveBusinessId(request), id, body);
    }
}
