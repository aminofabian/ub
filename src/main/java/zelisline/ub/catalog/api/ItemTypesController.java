package zelisline.ub.catalog.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemTypeRequest;
import zelisline.ub.catalog.api.dto.ItemTypeResponse;
import zelisline.ub.catalog.application.CatalogTaxonomyService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/item-types")
@RequiredArgsConstructor
public class ItemTypesController {

    private final CatalogTaxonomyService catalogTaxonomyService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<ItemTypeResponse> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.listItemTypes(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemTypeResponse create(@Valid @RequestBody CreateItemTypeRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.createItemType(TenantRequestIds.resolveBusinessId(request), body);
    }
}
