package zelisline.ub.catalog.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CategoryLinkedPriceRuleResponse;
import zelisline.ub.catalog.api.dto.CategoryResponse;
import zelisline.ub.catalog.api.dto.CategorySupplierSummaryResponse;
import zelisline.ub.catalog.api.dto.CategoryTreeNodeResponse;
import zelisline.ub.catalog.api.dto.CreateCategoryRequest;
import zelisline.ub.catalog.api.dto.CreateCategorySupplierLinkRequest;
import zelisline.ub.catalog.api.dto.ItemImageResponse;
import zelisline.ub.catalog.api.dto.PatchCategorySupplierLinkRequest;
import zelisline.ub.catalog.api.dto.PostCategoryPriceRuleRequest;
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

    @GetMapping("/tree")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<CategoryTreeNodeResponse> tree(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.categoryTree(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/{id}/children")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<CategoryResponse> children(@PathVariable("id") String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.categoryChildren(TenantRequestIds.resolveBusinessId(request), id);
    }

    @GetMapping("/{id}/price-rules")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<CategoryLinkedPriceRuleResponse> listPriceRules(@PathVariable("id") String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.listCategoryPriceRules(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/price-rules")
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public CategoryLinkedPriceRuleResponse linkPriceRule(
            @PathVariable("id") String id,
            @Valid @RequestBody PostCategoryPriceRuleRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.linkCategoryPriceRule(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/{id}/price-rules/{ruleId}")
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unlinkPriceRule(
            @PathVariable("id") String id,
            @PathVariable("ruleId") String ruleId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        catalogTaxonomyService.unlinkCategoryPriceRule(TenantRequestIds.resolveBusinessId(request), id, ruleId);
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

    @GetMapping("/{id}/images")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public List<ItemImageResponse> listImages(@PathVariable("id") String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.listCategoryImages(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping(value = "/{id}/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemImageResponse uploadImage(
            @PathVariable("id") String id,
            @RequestPart("file") MultipartFile file,
            @RequestParam(value = "altText", required = false) String altText,
            @RequestParam(value = "primary", required = false, defaultValue = "true") boolean primary,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        final byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (java.io.IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not read uploaded file");
        }
        return catalogTaxonomyService.uploadCategoryImageCloudinary(
                TenantRequestIds.resolveBusinessId(request),
                id,
                bytes,
                file.getOriginalFilename(),
                altText,
                primary);
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteImage(
            @PathVariable("id") String id,
            @PathVariable("imageId") String imageId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        catalogTaxonomyService.deleteCategoryImage(TenantRequestIds.resolveBusinessId(request), id, imageId);
    }

    @PostMapping("/{id}/supplier-links")
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public CategorySupplierSummaryResponse addSupplierLink(
            @PathVariable("id") String id,
            @Valid @RequestBody CreateCategorySupplierLinkRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.addCategorySupplierLink(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PatchMapping("/{id}/supplier-links/{supplierId}")
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    public CategorySupplierSummaryResponse patchSupplierLink(
            @PathVariable("id") String id,
            @PathVariable("supplierId") String supplierId,
            @Valid @RequestBody PatchCategorySupplierLinkRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return catalogTaxonomyService.patchCategorySupplierLink(
                TenantRequestIds.resolveBusinessId(request),
                id,
                supplierId,
                body.primary());
    }

    @DeleteMapping("/{id}/supplier-links/{supplierId}")
    @PreAuthorize("hasPermission(null, 'catalog.categories.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeSupplierLink(
            @PathVariable("id") String id,
            @PathVariable("supplierId") String supplierId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        catalogTaxonomyService.removeCategorySupplierLink(TenantRequestIds.resolveBusinessId(request), id, supplierId);
    }
}
