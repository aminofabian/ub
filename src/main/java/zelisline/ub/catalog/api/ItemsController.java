package zelisline.ub.catalog.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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

import zelisline.ub.catalog.api.dto.EffectivePricingContextResponse;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.api.dto.ItemImageResponse;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.api.dto.ItemSummaryResponse;
import zelisline.ub.catalog.api.dto.PatchItemRequest;
import zelisline.ub.catalog.api.dto.RegisterItemImageRequest;
import zelisline.ub.catalog.application.CategoryPricingResolutionService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.application.ItemCreateResult;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemsController {

    private final ItemCatalogService itemCatalogService;
    private final CategoryPricingResolutionService categoryPricingResolutionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public Page<ItemSummaryResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false, defaultValue = "false") boolean includeCategoryDescendants,
            @RequestParam(required = false, defaultValue = "false") boolean noBarcode,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            Pageable pageable,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemCatalogService.listItems(
                TenantRequestIds.resolveBusinessId(request),
                search,
                barcode,
                categoryId,
                includeCategoryDescendants,
                noBarcode,
                includeInactive,
                pageable
        );
    }

    @GetMapping("/{id}/pricing-context")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public EffectivePricingContextResponse pricingContext(@PathVariable("id") String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return categoryPricingResolutionService.resolve(TenantRequestIds.resolveBusinessId(request), id);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public ItemResponse getById(@PathVariable("id") String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return itemCatalogService.getItem(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    public ResponseEntity<ItemResponse> create(
            @Valid @RequestBody CreateItemRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        ItemCreateResult result = itemCatalogService.createItem(
                TenantRequestIds.resolveBusinessId(request),
                body,
                idempotencyKey
        );
        return ResponseEntity.status(result.httpStatus()).body(result.body());
    }

    @PatchMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    public ItemResponse patch(
            @PathVariable("id") String id,
            @Valid @RequestBody PatchItemRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemCatalogService.patchItem(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(
            @PathVariable("id") String id,
            @RequestParam(required = false, defaultValue = "false") boolean cascadeVariants,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        itemCatalogService.deleteItem(TenantRequestIds.resolveBusinessId(request), id, cascadeVariants);
    }

    @PostMapping("/{id}/variants")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemResponse createVariant(
            @PathVariable("id") String id,
            @Valid @RequestBody CreateVariantRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemCatalogService.createVariant(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PostMapping("/{id}/images")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemImageResponse registerItemImage(
            @PathVariable("id") String id,
            @Valid @RequestBody RegisterItemImageRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemCatalogService.registerItemImage(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PostMapping(value = "/{id}/images/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public ItemImageResponse uploadItemImage(
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
        return itemCatalogService.uploadItemImageCloudinary(
                TenantRequestIds.resolveBusinessId(request),
                id,
                bytes,
                file.getOriginalFilename(),
                altText,
                primary
        );
    }

    @DeleteMapping("/{id}/images/{imageId}")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteItemImage(
            @PathVariable("id") String id,
            @PathVariable("imageId") String imageId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        itemCatalogService.deleteItemImage(TenantRequestIds.resolveBusinessId(request), id, imageId);
    }
}
