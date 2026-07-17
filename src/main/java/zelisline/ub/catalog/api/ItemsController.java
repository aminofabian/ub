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
import zelisline.ub.catalog.api.dto.CatalogListScope;
import zelisline.ub.catalog.api.dto.CatalogRowType;
import zelisline.ub.catalog.api.dto.CatalogRowTypeCountsResponse;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.api.dto.GenerateProductDescriptionRequest;
import zelisline.ub.catalog.api.dto.GenerateProductDescriptionResponse;
import zelisline.ub.catalog.api.dto.ItemImageResponse;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.api.dto.ItemSummaryResponse;
import zelisline.ub.catalog.api.dto.ItemTimelineResponse;
import zelisline.ub.catalog.api.dto.PatchItemRequest;
import zelisline.ub.catalog.api.dto.RecordItemScanRequest;
import zelisline.ub.catalog.api.dto.RegisterItemImageRequest;
import zelisline.ub.catalog.api.dto.SuggestedSkuResponse;
import zelisline.ub.catalog.application.CategoryPricingResolutionService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.application.ItemCreateResult;
import zelisline.ub.catalog.application.ItemTimelineService;
import zelisline.ub.catalog.application.ProductDescriptionGeneratorService;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

import zelisline.ub.identity.repository.UserItemTypeRepository;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.tenancy.api.TenantRequestIds;

import java.util.List;
import java.util.Set;

@Validated
@RestController
@RequestMapping("/api/v1/items")
@RequiredArgsConstructor
public class ItemsController {

    private final ItemCatalogService itemCatalogService;
    private final ItemTimelineService itemTimelineService;
    private final CategoryPricingResolutionService categoryPricingResolutionService;
    private final ProductDescriptionGeneratorService productDescriptionGeneratorService;
    private final BranchResolutionService branchResolutionService;
    private final UserItemTypeRepository userItemTypeRepository;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public Page<ItemSummaryResponse> list(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false, defaultValue = "false") boolean includeCategoryDescendants,
            @RequestParam(required = false, defaultValue = "false") boolean noBarcode,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @RequestParam(required = false, defaultValue = "false") boolean noPrice,
            @RequestParam(required = false, defaultValue = "false") boolean zeroStock,
            @RequestParam(required = false, defaultValue = "false") boolean lowStock,
            @RequestParam(required = false, defaultValue = "false") boolean inactiveOnly,
            @RequestParam(required = false, defaultValue = "ALL") CatalogListScope catalogScope,
            @RequestParam(required = false) List<CatalogRowType> catalogRowTypes,
            @RequestParam(required = false) String excludeLinkedSupplierId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String itemTypeId,
            @RequestParam(required = false) Boolean isWeighed,
            Pageable pageable,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        List<String> allowedItemTypes = resolveCallerAllowedItemTypes(request, itemTypeId);
        return itemCatalogService.listItems(
                TenantRequestIds.resolveBusinessId(request),
                search,
                barcode,
                categoryId,
                includeCategoryDescendants,
                noBarcode,
                includeInactive,
                catalogScope,
                catalogRowTypes,
                excludeLinkedSupplierId,
                branchId,
                itemTypeId,
                allowedItemTypes,
                noPrice,
                zeroStock,
                lowStock,
                inactiveOnly,
                isWeighed,
                pageable
        );
    }

    @GetMapping("/row-type-counts")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public CatalogRowTypeCountsResponse rowTypeCounts(
            @RequestParam(required = false) String search,
            @RequestParam(required = false) String barcode,
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false, defaultValue = "false") boolean includeCategoryDescendants,
            @RequestParam(required = false, defaultValue = "false") boolean noBarcode,
            @RequestParam(required = false, defaultValue = "false") boolean includeInactive,
            @RequestParam(required = false, defaultValue = "ALL") CatalogListScope catalogScope,
            @RequestParam(required = false) String excludeLinkedSupplierId,
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String itemTypeId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        List<String> allowedItemTypes = resolveCallerAllowedItemTypes(request, itemTypeId);
        return itemCatalogService.countCatalogRowTypes(
                TenantRequestIds.resolveBusinessId(request),
                search,
                barcode,
                categoryId,
                includeCategoryDescendants,
                noBarcode,
                includeInactive,
                catalogScope,
                excludeLinkedSupplierId,
                branchId,
                itemTypeId,
                allowedItemTypes
        );
    }

    /**
     * For role-restricted callers (currently only {@code grocery_clerk}),
     * returns the list of item-type IDs they are allowed to see. Other roles
     * return {@code null}, which means "no restriction".
     *
     * <p>If the caller explicitly requested an {@code itemTypeId} that falls
     * outside their allowed set, we narrow the allowed set to the empty set
     * so the query short-circuits to an empty page rather than leaking items
     * from a forbidden department.</p>
     */
    private List<String> resolveCallerAllowedItemTypes(HttpServletRequest request, String requestedItemTypeId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        Object p = auth == null ? null : auth.getPrincipal();
        if (!(p instanceof TenantPrincipal tp)) {
            return null;
        }
        if (!branchResolutionService.isGroceryClerkRole(tp.roleId())) {
            return null;
        }
        List<String> assigned = userItemTypeRepository.findItemTypeIdsByUserId(tp.userId());
        String requested = requestedItemTypeId == null ? null : requestedItemTypeId.trim();
        if (requested != null && !requested.isEmpty()) {
            Set<String> allowed = Set.copyOf(assigned);
            if (!allowed.contains(requested)) {
                return List.of();
            }
        }
        return assigned;
    }

    @PostMapping("/generate-description")
    @PreAuthorize("hasPermission(null, 'catalog.items.write')")
    public GenerateProductDescriptionResponse generateDescription(
            @Valid @RequestBody GenerateProductDescriptionRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return new GenerateProductDescriptionResponse(productDescriptionGeneratorService.generate(body));
    }

    @GetMapping("/next-sku")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public SuggestedSkuResponse nextSku(
            @RequestParam(required = false) String categoryId,
            @RequestParam(required = false) String parentItemId,
            @RequestParam(required = false) String variantName,
            @RequestParam(required = false) String brand,
            @RequestParam(required = false) String size,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return new SuggestedSkuResponse(
                itemCatalogService.suggestNextSku(businessId, categoryId, parentItemId, variantName, brand, size));
    }

    @GetMapping("/{id}/pricing-context")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public EffectivePricingContextResponse pricingContext(@PathVariable("id") String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return categoryPricingResolutionService.resolve(TenantRequestIds.resolveBusinessId(request), id);
    }

    @GetMapping("/{id}/timeline")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public ItemTimelineResponse timeline(
            @PathVariable("id") String id,
            @RequestParam(required = false, defaultValue = "40") int limit,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemTimelineService.timeline(TenantRequestIds.resolveBusinessId(request), id, limit);
    }

    @PostMapping("/{id}/scans")
    @PreAuthorize(
            "hasPermission(null, 'catalog.items.read') or hasPermission(null, 'stocktake.run')"
    )
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recordScan(
            @PathVariable("id") String id,
            @Valid @RequestBody RecordItemScanRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        itemTimelineService.recordScan(
                TenantRequestIds.resolveBusinessId(request),
                id,
                body,
                CurrentTenantUser.auditActorId(request)
        );
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'catalog.items.read')")
    public ItemResponse getById(
            @PathVariable("id") String id,
            @RequestParam(required = false) String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return itemCatalogService.getItem(
                TenantRequestIds.resolveBusinessId(request),
                id,
                branchId);
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
                idempotencyKey,
                CurrentTenantUser.auditActorId(request)
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
        String actorUserId = CurrentTenantUser.auditActorId(request);
        return itemCatalogService.patchItem(
                TenantRequestIds.resolveBusinessId(request), id, body, actorUserId);
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
        itemCatalogService.deleteItem(
                TenantRequestIds.resolveBusinessId(request),
                id,
                cascadeVariants,
                CurrentTenantUser.auditActorId(request)
        );
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
        return itemCatalogService.createVariant(
                TenantRequestIds.resolveBusinessId(request),
                id,
                body,
                CurrentTenantUser.auditActorId(request)
        );
    }

    @PostMapping("/{id}/images")
    @PreAuthorize(
            "hasPermission(null, 'catalog.items.write') or hasPermission(null, 'stocktake.run')"
    )
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
    @PreAuthorize(
            "hasPermission(null, 'catalog.items.write') or hasPermission(null, 'stocktake.run')"
    )
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
