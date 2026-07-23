package zelisline.ub.globalcatalog.application;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.application.CatalogTaxonomyService;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.globalcatalog.api.dto.AdoptLineRequest;
import zelisline.ub.globalcatalog.api.dto.AdoptRequest;
import zelisline.ub.globalcatalog.api.dto.AdoptResponse;
import zelisline.ub.globalcatalog.api.dto.AdoptResultLineResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCatalogMetaResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalCategoryResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalProductImageResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalProductPackDetailResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalProductPackSummaryResponse;
import zelisline.ub.globalcatalog.api.dto.GlobalProductResponse;
import zelisline.ub.globalcatalog.api.dto.PreviewAdoptRequest;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductImage;
import zelisline.ub.globalcatalog.domain.GlobalProductPack;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductPackRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.platform.persistence.DataIntegrityProblems;
import zelisline.ub.tenancy.application.BusinessProfileSettingsService;
import zelisline.ub.tenancy.application.RegionCatalogAuditService;
import zelisline.ub.tenancy.domain.Branch;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BranchRepository;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class GlobalCatalogService {

    /** Placeholder item id for SKUs reserved by earlier lines in the same adopt batch. */
    private static final String BATCH_SKU_PLACEHOLDER = "__batch_reserved__";

    private static final String STATUS_PUBLISHED = GlobalProductStatus.PUBLISHED;

    private final GlobalCatalogRepository globalCatalogRepository;
    private final GlobalCategoryRepository globalCategoryRepository;
    private final GlobalProductRepository globalProductRepository;
    private final GlobalProductPackRepository globalProductPackRepository;
    private final BranchRepository branchRepository;
    private final ItemRepository itemRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final CategoryRepository categoryRepository;
    private final CatalogTaxonomyService catalogTaxonomyService;
    private final GlobalCatalogAdoptLineExecutor adoptLineExecutor;
    private final GlobalProductImageGalleryService galleryService;
    private final GlobalCatalogResolver globalCatalogResolver;
    private final RegionCatalogAuditService regionCatalogAuditService;
    private final BusinessRepository businessRepository;
    private final BusinessProfileSettingsService businessProfileSettingsService;

    @Transactional(readOnly = true)
    public GlobalCatalogMetaResponse getCatalogMeta(String businessId) {
        ResolvedGlobalCatalog resolved = globalCatalogResolver.resolveDetailedForBusiness(businessId);
        GlobalCatalog catalog = resolved.catalog();
        regionCatalogAuditService.catalogResolved(businessId, catalog, resolved.via());
        List<GlobalCategoryResponse> categories = globalCategoryRepository
                .findByCatalogIdAndActiveTrueOrderByPositionAsc(catalog.getId())
                .stream()
                .map(this::toCategoryResponse)
                .toList();

        List<GlobalProductPackSummaryResponse> packs = globalProductPackRepository
                .findByCatalogIdAndStatusOrderBySortOrderAsc(catalog.getId(), STATUS_PUBLISHED)
                .stream()
                .map(p -> toPackSummaryResponse(p, catalog.getId()))
                .toList();

        return new GlobalCatalogMetaResponse(
                catalog.getId(),
                catalog.getCode(),
                catalog.getName(),
                catalog.getCurrency(),
                categories,
                packs
        );
    }

    @Transactional(readOnly = true)
    public Page<GlobalProductResponse> listProducts(
            String businessId,
            String categoryId,
            String q,
            String barcode,
            boolean onlyNotImported,
            Pageable pageable
    ) {
        GlobalCatalog catalog = resolveCatalog(businessId);
        TenantCatalogMatchIndex matchIndex = tenantCatalogMatchIndex(businessId);
        Collection<String> categoryIds = expandCategoryFilter(catalog.getId(), blankToNull(categoryId));
        boolean categoryIdsEmpty = categoryIds.isEmpty();
        // Hibernate rejects empty IN lists — use a sentinel when no category filter.
        Collection<String> categoryIdsParam =
                categoryIdsEmpty ? List.of("__no_category_filter__") : categoryIds;

        if (onlyNotImported) {
            Page<GlobalProduct> all = globalProductRepository.search(
                    catalog.getId(),
                    STATUS_PUBLISHED,
                    categoryIdsParam,
                    categoryIdsEmpty,
                    blankToNull(q),
                    blankToNull(barcode),
                    Pageable.unpaged());

            List<GlobalProduct> availableProducts = all.getContent().stream()
                    .filter(gp -> !matchIndex.matches(gp))
                    .toList();
            Map<String, List<GlobalProductImage>> imagesByProduct = imagesByProductId(availableProducts);
            List<GlobalProductResponse> available = availableProducts.stream()
                    .map(gp -> toProductResponse(
                            gp, matchIndex, imagesByProduct.getOrDefault(gp.getId(), List.of())))
                    .toList();

            int start = (int) pageable.getOffset();
            int end = Math.min(start + pageable.getPageSize(), available.size());
            List<GlobalProductResponse> slice = start >= available.size()
                    ? List.of()
                    : available.subList(start, end);
            return new PageImpl<>(slice, pageable, available.size());
        }

        Pageable p = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize());
        Page<GlobalProduct> page = globalProductRepository.search(
                catalog.getId(),
                STATUS_PUBLISHED,
                categoryIdsParam,
                categoryIdsEmpty,
                blankToNull(q),
                blankToNull(barcode),
                p);

        Map<String, List<GlobalProductImage>> imagesByProduct = imagesByProductId(page.getContent());
        return page.map(gp -> toProductResponse(
                gp, matchIndex, imagesByProduct.getOrDefault(gp.getId(), List.of())));
    }

    /**
     * When filtering by a parent department, include products in all descendant categories.
     * Empty collection means "no category filter".
     */
    private Collection<String> expandCategoryFilter(String catalogId, String categoryId) {
        if (categoryId == null) {
            return List.of();
        }
        List<GlobalCategory> all =
                globalCategoryRepository.findByCatalogIdAndActiveTrueOrderByPositionAsc(catalogId);
        Map<String, List<String>> childrenByParent = new HashMap<>();
        for (GlobalCategory cat : all) {
            String parent = blankToNull(cat.getParentId());
            if (parent == null) {
                continue;
            }
            childrenByParent.computeIfAbsent(parent, ignored -> new ArrayList<>()).add(cat.getId());
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(categoryId);
        while (!queue.isEmpty()) {
            String id = queue.removeFirst();
            if (!ids.add(id)) {
                continue;
            }
            List<String> children = childrenByParent.get(id);
            if (children != null) {
                queue.addAll(children);
            }
        }
        return ids;
    }

    @Transactional(readOnly = true)
    public GlobalProductResponse getProduct(String businessId, String globalProductId) {
        GlobalCatalog catalog = resolveCatalog(businessId);
        GlobalProduct gp = globalProductRepository
                .findByIdAndCatalogIdAndStatus(globalProductId, catalog.getId(), STATUS_PUBLISHED)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global product not found"));

        TenantCatalogMatchIndex matchIndex = tenantCatalogMatchIndex(businessId);
        return toProductResponse(gp, matchIndex, galleryService.listForProduct(gp.getId()));
    }

    @Transactional(readOnly = true)
    public List<GlobalProductResponse> lookup(String businessId, String barcode, String q) {
        GlobalCatalog catalog = resolveCatalog(businessId);
        if ((barcode == null || barcode.isBlank()) && (q == null || q.isBlank())) {
            return List.of();
        }
        List<GlobalProduct> products = globalProductRepository.lookup(
                catalog.getId(),
                STATUS_PUBLISHED,
                blankToNull(barcode),
                blankToNull(q),
                PageRequest.of(0, 20)
        );
        TenantCatalogMatchIndex matchIndex = tenantCatalogMatchIndex(businessId);
        Map<String, List<GlobalProductImage>> imagesByProduct = imagesByProductId(products);
        return products.stream()
                .map(gp -> toProductResponse(
                        gp, matchIndex, imagesByProduct.getOrDefault(gp.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public GlobalProductPackDetailResponse getPack(String businessId, String packId, boolean onlyNotImported) {
        GlobalCatalog catalog = resolveCatalog(businessId);
        GlobalProductPack pack = globalProductPackRepository.findByIdAndCatalogId(packId, catalog.getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Pack not found"));

        List<String> productIds = globalProductPackRepository.findProductIdsByPackId(pack.getId());
        if (productIds.isEmpty()) {
            return new GlobalProductPackDetailResponse(pack.getId(), pack.getCode(), pack.getName(), pack.getDescription(), List.of());
        }

        List<GlobalProduct> products = globalProductRepository.findAllById(productIds);
        Map<String, GlobalProduct> byId = products.stream()
                .collect(Collectors.toMap(GlobalProduct::getId, gp -> gp, (a, b) -> a, LinkedHashMap::new));

        TenantCatalogMatchIndex matchIndex = tenantCatalogMatchIndex(businessId);
        Map<String, List<GlobalProductImage>> imagesByProduct = imagesByProductId(
                productIds.stream().map(byId::get).filter(gp -> gp != null).toList());

        List<GlobalProductResponse> ordered = productIds.stream()
                .map(byId::get)
                .filter(gp -> gp != null)
                .map(gp -> toProductResponse(
                        gp, matchIndex, imagesByProduct.getOrDefault(gp.getId(), List.of())))
                .filter(gp -> !onlyNotImported || !gp.alreadyImported())
                .toList();

        return new GlobalProductPackDetailResponse(pack.getId(), pack.getCode(), pack.getName(), pack.getDescription(), ordered);
    }

    @Transactional(readOnly = true)
    public AdoptResponse previewAdopt(String businessId, PreviewAdoptRequest request) {
        return runAdopt(
                businessId,
                null,
                request.lines(),
                true,
                null,
                Boolean.TRUE.equals(request.createMissingCategories())
        );
    }

    public AdoptResponse adopt(String businessId, AdoptRequest request, String actorUserId) {
        AdoptResponse response = runAdopt(
                businessId,
                request.openingBranchId(),
                request.lines(),
                false,
                actorUserId,
                Boolean.TRUE.equals(request.createMissingCategories())
        );
        if (request.packId() != null && !request.packId().isBlank()) {
            emitPackAdopted(businessId, request.packId().trim(), response.importedCount());
        }
        return response;
    }

    private void emitPackAdopted(String businessId, String packId, int importedCount) {
        GlobalCatalog catalog = resolveCatalog(businessId);
        String storeKitId = globalProductPackRepository.findById(packId)
                .map(GlobalProductPack::getStoreKitId)
                .orElse(null);
        List<String> storeTypes = List.of();
        try {
            Business business = businessRepository.findById(businessId).orElse(null);
            if (business != null) {
                var profile = businessProfileSettingsService.readFromSettingsJson(business.getSettings());
                if (profile.storeTypes() != null) {
                    storeTypes = profile.storeTypes();
                } else if (profile.storeType() != null) {
                    storeTypes = List.of(profile.storeType());
                }
            }
        } catch (Exception ignored) {
            // telemetry only
        }
        regionCatalogAuditService.packAdopted(
                businessId,
                catalog.getCode(),
                packId,
                storeKitId,
                storeTypes,
                importedCount
        );
    }

    private AdoptResponse runAdopt(
            String businessId,
            String openingBranchId,
            List<AdoptLineRequest> lines,
            boolean dryRun,
            String actorUserId,
            boolean createMissingCategories
    ) {
        GlobalCatalog catalog = resolveCatalog(businessId);
        Branch branch = null;
        if (!dryRun) {
            branch = branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(openingBranchId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Opening branch not found"));
        }

        String defaultItemTypeId = itemTypeRepository.findByBusinessIdAndTypeKey(businessId, "goods")
                .orElseGet(() -> itemTypeRepository.findByBusinessIdAndIsDefaultTrue(businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No default item type found for business")))
                .getId();

        List<String> requestedIds = lines.stream().map(AdoptLineRequest::globalProductId).distinct().toList();
        List<String> publishedIds = globalProductRepository.findPublishedIdsByCatalogAndIdIn(
                catalog.getId(), STATUS_PUBLISHED, requestedIds);
        Set<String> publishedIdSet = Set.copyOf(publishedIds);

        List<Item> tenantItems = itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId);
        TenantCatalogMatchIndex matchIndex = TenantCatalogMatchIndex.fromItems(tenantItems);

        Map<String, String> categorySlugHintById = new HashMap<>();
        Map<String, String> categoryNameById = new HashMap<>();
        for (GlobalCategory category : globalCategoryRepository.findByCatalogIdAndActiveTrueOrderByPositionAsc(catalog.getId())) {
            categorySlugHintById.put(
                    category.getId(),
                    category.getTenantCategorySlugHint() == null ? "" : category.getTenantCategorySlugHint());
            categoryNameById.put(category.getId(), category.getName());
        }
        Map<String, String> createdCategoryIdBySlug = new HashMap<>();

        Map<String, String> skuToItemId = new HashMap<>();
        for (Item tenantItem : tenantItems) {
            if (tenantItem.getSku() != null && !tenantItem.getSku().isBlank()) {
                skuToItemId.putIfAbsent(tenantItem.getSku().trim(), tenantItem.getId());
            }
        }

        List<AdoptResultLineResponse> results = new ArrayList<>();
        int importedCount = 0;
        int skippedCount = 0;
        final String effectiveActor = actorUserIdOrSystem(actorUserId);

        for (AdoptLineRequest line : lines) {
            String gpId = line.globalProductId();
            if (!publishedIdSet.contains(gpId)) {
                results.add(new AdoptResultLineResponse(gpId, "error_not_found", null, null, "Global product not found or not published"));
                continue;
            }

            GlobalProduct gp = globalProductRepository.findById(gpId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global product not found"));

            String existingItemId = matchIndex.findMatchingItemId(gp);
            if (existingItemId != null) {
                Item existing = tenantItems.stream()
                        .filter(i -> i.getId().equals(existingItemId))
                        .findFirst()
                        .orElse(null);
                String existingSku = existing != null ? existing.getSku() : null;
                results.add(new AdoptResultLineResponse(gpId, "skip_already_imported", existingItemId, existingSku, "Already in catalog"));
                skippedCount++;
                continue;
            }

            String requestedSku = line.sku() != null ? line.sku().trim() : null;
            String sku = requestedSku != null && !requestedSku.isBlank()
                    ? requestedSku
                    : (gp.getSkuTemplate() != null && !gp.getSkuTemplate().isBlank()
                            ? gp.getSkuTemplate()
                            : null);
            String barcode = ItemCatalogService.normalizeBarcode(gp.getBarcode());

            if (sku != null) {
                String conflictItemId = resolveSkuConflictItemId(businessId, sku, skuToItemId);
                if (conflictItemId != null) {
                String resolution = normalizeSkuConflictResolution(line.onSkuConflict());

                if ("merge".equals(resolution) && !BATCH_SKU_PLACEHOLDER.equals(conflictItemId)) {
                    Item conflictItem = resolveConflictItem(businessId, conflictItemId, tenantItems);
                    if (conflictItem == null) {
                        results.add(new AdoptResultLineResponse(
                                gpId, "error_not_found", null, sku, "Existing product not found"));
                        continue;
                    }
                    String linkError = globalProductLinkError(conflictItem, gp.getId());
                    if (linkError != null) {
                        results.add(new AdoptResultLineResponse(gpId, "error_already_linked", conflictItemId, sku, linkError));
                        continue;
                    }
                    if (dryRun) {
                        results.add(new AdoptResultLineResponse(
                                gpId, "ready_merge", conflictItemId, conflictItem.getSku(), "Will link to existing product"));
                        continue;
                    }
                    BigDecimal mergePrice = line.sellingPrice() != null
                            ? line.sellingPrice()
                            : gp.getRecommendedSellingPrice();
                    try {
                        AdoptResultLineResponse merged = adoptLineExecutor.mergeLine(
                                businessId,
                                new GlobalCatalogAdoptLineExecutor.MergeLineCommand(
                                        gp.getId(),
                                        conflictItem.getId(),
                                        branch.getId(),
                                        mergePrice,
                                        gp.getImageUrl()),
                                effectiveActor);
                        merged = adoptLineExecutor.attachGalleryAfterCommit(
                                businessId, merged, gp.getId(), gp.getImageUrl(), true);
                        itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(conflictItem.getId(), businessId)
                                .ifPresent(matchIndex::register);
                        importedCount++;
                        results.add(merged);
                    } catch (Exception ex) {
                        results.add(mapAdoptLineFailure(ex, businessId, gpId, sku, barcode, skuToItemId));
                        skippedCount++;
                    }
                    continue;
                }

                if ("rename".equals(resolution)) {
                    String explicitRename = line.sku() != null ? line.sku().trim() : "";
                    if (!explicitRename.isBlank()
                            && !explicitRename.equals(sku)
                            && resolveSkuConflictItemId(businessId, explicitRename, skuToItemId) == null) {
                        sku = explicitRename;
                    } else {
                        String renamed = allocateUniqueSku(businessId, sku, skuToItemId);
                        if (renamed == null) {
                            results.add(new AdoptResultLineResponse(
                                    gpId, "error_invalid_sku", null, sku, "Could not find an available SKU suffix"));
                            continue;
                        }
                        sku = renamed;
                    }
                } else {
                    appendSkuConflictSkip(results, gpId, sku, conflictItemId);
                    skippedCount++;
                    continue;
                }
                reserveSkuForBatch(skuToItemId, sku);
                }
            }

            if (barcode != null) {
                var barcodeHolder = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode);
                if (barcodeHolder.isPresent()) {
                    results.add(new AdoptResultLineResponse(
                            gpId,
                            "skip_barcode_conflict",
                            barcodeHolder.get().getId(),
                            sku,
                            "Barcode already in use"));
                    skippedCount++;
                    continue;
                }
            }

            if (dryRun) {
                String slugHint = categorySlugHintById.getOrDefault(gp.getGlobalCategoryId(), "");
                CategoryResolveResult categoryResult = resolveTenantCategory(
                        businessId,
                        line.categoryId(),
                        slugHint,
                        categoryNameById.get(gp.getGlobalCategoryId()),
                        createMissingCategories,
                        true,
                        createdCategoryIdBySlug
                );
                String message = "Will import";
                if (categoryResult.wouldCreate()) {
                    message = "Will import (create category " + slugHint.trim() + ")";
                }
                results.add(new AdoptResultLineResponse(gpId, "ready", null, sku, message));
                reserveSkuForBatch(skuToItemId, sku);
                continue;
            }

            String slugHint = categorySlugHintById.getOrDefault(gp.getGlobalCategoryId(), "");
            String categoryId = resolveTenantCategory(
                    businessId,
                    line.categoryId(),
                    slugHint,
                    categoryNameById.get(gp.getGlobalCategoryId()),
                    createMissingCategories,
                    false,
                    createdCategoryIdBySlug
            ).categoryId();

            String size = blankToNull(gp.getSize());
            if (size == null && blankToNull(gp.getVariantName()) != null) {
                String variant = gp.getVariantName().trim();
                size = variant.length() > 50 ? variant.substring(0, 50) : variant;
            }
            // Package templates adopt as standalone stockable SKUs (no parent link yet).
            boolean stocked = gp.isStocked() || gp.isPackageVariant();

            CreateItemRequest createReq = new CreateItemRequest(
                    sku,
                    barcode,
                    gp.getName(),
                    gp.getDescription(),
                    defaultItemTypeId,
                    categoryId,
                    null,
                    gp.getUnitType(),
                    gp.isWeighed(),
                    gp.isSellable(),
                    stocked,
                    gp.getPackagingUnitName(),
                    gp.getPackagingUnitQty(),
                    null,
                    null,
                    line.buyingPrice() != null ? line.buyingPrice() : gp.getRecommendedBuyingPrice(),
                    null,
                    line.minStockLevel() != null ? line.minStockLevel() : gp.getDefaultMinStockLevel(),
                    line.reorderLevel() != null ? line.reorderLevel() : gp.getDefaultReorderLevel(),
                    line.reorderQty() != null ? line.reorderQty() : gp.getDefaultReorderQty(),
                    gp.getExpiresAfterDays(),
                    gp.isHasExpiry(),
                    gp.getImageUrl(),
                    gp.getBrand(),
                    size,
                    null
            );

            reserveSkuForBatch(skuToItemId, sku);

            BigDecimal sellingPrice = line.sellingPrice() != null
                    ? line.sellingPrice()
                    : gp.getRecommendedSellingPrice();
            BigDecimal openingQty = line.openingQty();
            BigDecimal openingUnitCost = line.openingUnitCost() != null && line.openingUnitCost().compareTo(BigDecimal.ZERO) > 0
                    ? line.openingUnitCost()
                    : (createReq.buyingPrice() != null ? createReq.buyingPrice() : null);

            try {
                AdoptResultLineResponse imported = adoptLineExecutor.importLine(
                        businessId,
                        new GlobalCatalogAdoptLineExecutor.ImportLineCommand(
                                gpId,
                                createReq,
                                branch.getId(),
                                sellingPrice,
                                openingQty,
                                openingUnitCost,
                                gp.getImageUrl()),
                        effectiveActor);
                imported = adoptLineExecutor.attachGalleryAfterCommit(
                        businessId, imported, gp.getId(), gp.getImageUrl(), false);
                if (imported.sku() != null && !imported.sku().isBlank()) {
                    skuToItemId.put(imported.sku().trim(), imported.itemId());
                }
                itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(imported.itemId(), businessId)
                        .ifPresent(matchIndex::register);
                importedCount++;
                results.add(imported);
            } catch (Exception ex) {
                results.add(mapAdoptLineFailure(ex, businessId, gpId, sku, barcode, skuToItemId));
                skippedCount++;
            }
        }

        return new AdoptResponse(importedCount, skippedCount, results);
    }

    private static String normalizeSkuConflictResolution(String raw) {
        if (raw == null || raw.isBlank()) {
            return "skip";
        }
        return raw.trim().toLowerCase();
    }

    private String resolveSkuConflictItemId(String businessId, String sku, Map<String, String> skuToItemId) {
        if (sku == null || sku.isBlank()) {
            return null;
        }
        String trimmed = sku.trim();
        String fromBatch = skuToItemId.get(trimmed);
        if (fromBatch != null) {
            return fromBatch;
        }
        return itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(businessId, trimmed)
                .map(Item::getId)
                .orElse(null);
    }

    private Item resolveConflictItem(String businessId, String conflictItemId, List<Item> tenantItems) {
        if (conflictItemId == null || conflictItemId.isBlank() || BATCH_SKU_PLACEHOLDER.equals(conflictItemId)) {
            return null;
        }
        Optional<Item> fromSnapshot = tenantItems.stream()
                .filter(i -> i.getId().equals(conflictItemId))
                .findFirst();
        if (fromSnapshot.isPresent()) {
            return fromSnapshot.get();
        }
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(conflictItemId, businessId)
                .orElse(null);
    }

    private static void reserveSkuForBatch(Map<String, String> skuToItemId, String sku) {
        if (sku == null || sku.isBlank()) {
            return;
        }
        skuToItemId.putIfAbsent(sku.trim(), BATCH_SKU_PLACEHOLDER);
    }

    private static void appendSkuConflictSkip(
            List<AdoptResultLineResponse> results,
            String gpId,
            String sku,
            String conflictItemId
    ) {
        results.add(new AdoptResultLineResponse(
                gpId, "skip_sku_conflict", conflictItemId, sku, "SKU already in use"));
    }

    private void appendBarcodeConflictSkip(
            List<AdoptResultLineResponse> results,
            String gpId,
            String sku,
            String barcode,
            String businessId
    ) {
        String conflictItemId = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode)
                .map(Item::getId)
                .orElse(null);
        results.add(new AdoptResultLineResponse(
                gpId, "skip_barcode_conflict", conflictItemId, sku, "Barcode already in use"));
    }

    private AdoptResultLineResponse mapAdoptLineFailure(
            Exception ex,
            String businessId,
            String gpId,
            String sku,
            String barcode,
            Map<String, String> skuToItemId
    ) {
        if (ex instanceof ResponseStatusException responseEx) {
            if (DataIntegrityProblems.isDuplicateSku(responseEx)) {
                return new AdoptResultLineResponse(
                        gpId,
                        "skip_sku_conflict",
                        resolveSkuConflictItemId(businessId, sku, skuToItemId),
                        sku,
                        "SKU already in use");
            }
            if (DataIntegrityProblems.isDuplicateBarcode(responseEx)) {
                String conflictItemId = barcode != null
                        ? itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode)
                                .map(Item::getId)
                                .orElse(null)
                        : null;
                return new AdoptResultLineResponse(
                        gpId, "skip_barcode_conflict", conflictItemId, sku, "Barcode already in use");
            }
            String message = responseEx.getReason() != null ? responseEx.getReason() : "Could not import this row";
            return new AdoptResultLineResponse(gpId, "error_import", null, sku, message);
        }
        if (ex instanceof DataIntegrityViolationException dataEx) {
            if (DataIntegrityProblems.isDuplicateSku(dataEx)) {
                return new AdoptResultLineResponse(
                        gpId,
                        "skip_sku_conflict",
                        resolveSkuConflictItemId(businessId, sku, skuToItemId),
                        sku,
                        "SKU already in use");
            }
            if (DataIntegrityProblems.isDuplicateBarcode(dataEx)) {
                String conflictItemId = barcode != null
                        ? itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode)
                                .map(Item::getId)
                                .orElse(null)
                        : null;
                return new AdoptResultLineResponse(
                        gpId, "skip_barcode_conflict", conflictItemId, sku, "Barcode already in use");
            }
            return new AdoptResultLineResponse(
                    gpId,
                    "error_data_integrity",
                    null,
                    sku,
                    "Could not import this row due to a database constraint");
        }
        String message = ex.getMessage() != null ? ex.getMessage() : "Could not import this row";
        return new AdoptResultLineResponse(gpId, "error_import", null, sku, message);
    }

    private static String globalProductLinkError(Item existing, String globalProductId) {
        String linked = existing.getGlobalProductSourceId();
        if (linked != null && !linked.isBlank() && !linked.equals(globalProductId)) {
            return "Existing product is already linked to another catalog item";
        }
        return null;
    }

    private String allocateUniqueSku(String businessId, String baseSku, Map<String, String> skuToItemId) {
        String root = baseSku.trim();
        for (int suffix = 2; suffix <= 99; suffix++) {
            String candidate = root + "-" + suffix;
            if (!skuToItemId.containsKey(candidate)
                    && !itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private CategoryResolveResult resolveTenantCategory(
            String businessId,
            String explicitCategoryId,
            String slugHint,
            String categoryDisplayName,
            boolean createMissingCategories,
            boolean dryRun,
            Map<String, String> createdCategoryIdBySlug
    ) {
        if (explicitCategoryId != null && !explicitCategoryId.isBlank()) {
            boolean exists = categoryRepository.findByIdAndBusinessId(explicitCategoryId, businessId).isPresent();
            return new CategoryResolveResult(exists ? explicitCategoryId : null, false);
        }
        if (slugHint == null || slugHint.isBlank()) {
            return new CategoryResolveResult(null, false);
        }
        String hint = slugHint.trim().toLowerCase(java.util.Locale.ROOT);
        if (createdCategoryIdBySlug.containsKey(hint)) {
            return new CategoryResolveResult(createdCategoryIdBySlug.get(hint), false);
        }
        Optional<Category> active = categoryRepository.findByBusinessIdAndSlugAndActiveTrue(businessId, hint);
        if (active.isPresent()) {
            createdCategoryIdBySlug.put(hint, active.get().getId());
            return new CategoryResolveResult(active.get().getId(), false);
        }
        if (!createMissingCategories) {
            return new CategoryResolveResult(null, false);
        }
        if (dryRun) {
            createdCategoryIdBySlug.put(hint, BATCH_SKU_PLACEHOLDER);
            return new CategoryResolveResult(null, true);
        }
        Category created = catalogTaxonomyService.ensureActiveCategoryBySlug(
                businessId,
                hint,
                categoryDisplayName
        );
        createdCategoryIdBySlug.put(hint, created.getId());
        return new CategoryResolveResult(created.getId(), true);
    }

    private record CategoryResolveResult(String categoryId, boolean wouldCreate) {
    }

    private TenantCatalogMatchIndex tenantCatalogMatchIndex(String businessId) {
        return TenantCatalogMatchIndex.fromItems(itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId));
    }

    private GlobalCatalog resolveCatalog(String businessId) {
        return globalCatalogResolver.resolveForBusiness(businessId);
    }

    private GlobalCategoryResponse toCategoryResponse(GlobalCategory c) {
        return new GlobalCategoryResponse(
                c.getId(),
                c.getName(),
                c.getSlug(),
                c.getPosition(),
                c.getTenantCategorySlugHint(),
                c.getParentId());
    }

    private GlobalProductPackSummaryResponse toPackSummaryResponse(GlobalProductPack pack, String catalogId) {
        int count = globalProductPackRepository.findProductIdsByPackId(pack.getId()).size();
        return new GlobalProductPackSummaryResponse(
                pack.getId(),
                pack.getCode(),
                pack.getName(),
                pack.getDescription(),
                pack.getStoreKitId(),
                count,
                pack.getSortOrder());
    }

    private Map<String, List<GlobalProductImage>> imagesByProductId(List<GlobalProduct> products) {
        if (products == null || products.isEmpty()) {
            return Map.of();
        }
        List<String> ids = products.stream().map(GlobalProduct::getId).toList();
        Map<String, List<GlobalProductImage>> out = new HashMap<>();
        for (GlobalProductImage image : galleryService.listForProducts(ids)) {
            out.computeIfAbsent(image.getGlobalProductId(), ignored -> new ArrayList<>()).add(image);
        }
        return out;
    }

    private GlobalProductResponse toProductResponse(
            GlobalProduct gp,
            TenantCatalogMatchIndex matchIndex,
            List<GlobalProductImage> images
    ) {
        String adoptedItemId = matchIndex.findMatchingItemId(gp);
        boolean alreadyImported = adoptedItemId != null;
        List<GlobalProductImageResponse> imageResponses = images == null
                ? List.of()
                : images.stream()
                        .map(img -> new GlobalProductImageResponse(
                                img.getId(),
                                img.getImageUrl(),
                                img.getSortOrder(),
                                img.getAltText(),
                                img.getWidth(),
                                img.getHeight()))
                        .toList();
        String coverUrl = blankToNull(gp.getImageUrl());
        if (coverUrl == null && !imageResponses.isEmpty()) {
            coverUrl = imageResponses.get(0).imageUrl();
        }
        return new GlobalProductResponse(
                gp.getId(),
                gp.getCatalogId(),
                gp.getGlobalCategoryId(),
                null,
                gp.getSkuTemplate(),
                gp.getName(),
                gp.getBrand(),
                gp.getSize(),
                gp.getVariantName(),
                gp.getDescription(),
                gp.getBarcode(),
                gp.getUnitType(),
                gp.isWeighed(),
                gp.isSellable(),
                gp.isStocked(),
                gp.isPackageVariant(),
                gp.getPackagingUnitName(),
                gp.getPackagingUnitQty(),
                gp.getRecommendedBuyingPrice(),
                gp.getRecommendedSellingPrice(),
                gp.getSuggestedMarginPct(),
                gp.getDefaultReorderLevel(),
                gp.getDefaultReorderQty(),
                gp.getDefaultMinStockLevel(),
                gp.isHasExpiry(),
                gp.getExpiresAfterDays(),
                coverUrl,
                imageResponses,
                gp.getItemTypeKeyHint(),
                gp.getSortOrder(),
                alreadyImported,
                adoptedItemId
        );
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String actorUserIdOrSystem(String actorUserId) {
        return actorUserId != null && !actorUserId.isBlank() ? actorUserId : "system";
    }
}
