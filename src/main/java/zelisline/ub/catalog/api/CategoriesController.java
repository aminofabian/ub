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
import zelisline.ub.catalog.api.dto.CategoryResponse;
import zelisline.ub.catalog.api.dto.CreateCategoryRequest;
import zelisline.ub.catalog.api.dto.PatchCategoryRequest;
import zelisline.ub.catalog.application.CatalogTaxonomyService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoriesController {

    private final CatalogTaxonomyService catalogTaxonomyService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<CategoryResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.listCategories(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryResponse create(@Valid @RequestBody CreateCategoryRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.createCategory(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    public CategoryResponse patch(
            @PathVariable("id") String id,
            @Valid @RequestBody PatchCategoryRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.patchCategory(TenantRequestIds.resolveBusinessId(request), id, body);
    }
}
