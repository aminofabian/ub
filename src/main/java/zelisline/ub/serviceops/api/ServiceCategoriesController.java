package zelisline.ub.serviceops.api;

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
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.serviceops.api.dto.CreateServiceCategoryRequest;
import zelisline.ub.serviceops.api.dto.PatchServiceCategoryRequest;
import zelisline.ub.serviceops.api.dto.ServiceCategoryResponse;
import zelisline.ub.serviceops.application.ServiceCatalogService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/service-categories")
@RequiredArgsConstructor
public class ServiceCategoriesController {

    private final ServiceCatalogService serviceCatalogService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')")
    public List<ServiceCategoryResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return serviceCatalogService.listCategories(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public ServiceCategoryResponse create(
            @Valid @RequestBody CreateServiceCategoryRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return serviceCatalogService.createCategory(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public ServiceCategoryResponse patch(
            @PathVariable String id,
            @Valid @RequestBody PatchServiceCategoryRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return serviceCatalogService.updateCategory(TenantRequestIds.resolveBusinessId(request), id, body);
    }
}
