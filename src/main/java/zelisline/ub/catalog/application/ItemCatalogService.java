package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import zelisline.ub.catalog.api.dto.CatalogListScope;
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.api.dto.ItemImageResponse;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.api.dto.ItemSummaryResponse;
import zelisline.ub.catalog.api.dto.PatchItemRequest;
import zelisline.ub.catalog.api.dto.RegisterItemImageRequest;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.IdempotencyKey;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.domain.ItemImageStorageProvider;
import zelisline.ub.catalog.repository.AisleRepository;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.inventory.repository.StockTakeChecklistItemRepository;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.suppliers.application.SupplierLinkProvisioner;
import zelisline.ub.sync.application.SyncConflictService;
import zelisline.ub.tenancy.repository.BranchRepository;

@Service
@RequiredArgsConstructor
public class ItemCatalogService {

    public static final String ROUTE_POST_ITEMS = "POST /api/v1/items";

    private static final Logger log = LoggerFactory.getLogger(ItemCatalogService.class);

    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final CategoryRepository categoryRepository;
    private final AisleRepository aisleRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final SupplierLinkProvisioner supplierLinkProvisioner;
    private final CloudinaryImageService cloudinaryImageService;
    private final BranchRepository branchRepository;
    private final InventoryBatchRepository inventoryBatchRepository;
    private final StockTakeChecklistItemRepository stockTakeChecklistItemRepository;
    private final PricingService pricingService;
    private final SkuGenerationService skuGenerationService;
    private final SyncConflictService syncConflictService;

    @Transactional(readOnly = true)
    public Page<ItemSummaryResponse> listItems(
            String businessId,
            String search,
            String barcodeExact,
            String categoryId,
            boolean includeCategoryDescendants,
            boolean noBarcode,
            boolean includeInactive,
            CatalogListScope catalogListScope,
            String excludeLinkedSupplierId,
            String branchIdForStock,
            String itemTypeId,
            Pageable pageable
    ) {
        String q = blankToNull(search);
        String bc = blankToNull(barcodeExact);
        String cat = blankToNull(categoryId);
        CatalogListScope scope = catalogListScope != null ? catalogListScope : CatalogListScope.ALL;
        boolean includeAllScopes = scope == CatalogListScope.ALL;
        boolean parentsOnly = scope == CatalogListScope.PARENTS_ONLY;
        boolean variantsOnly = scope == CatalogListScope.VARIANTS_ONLY;
        boolean skusOnly = scope == CatalogListScope.SKUS_ONLY;
        Sort defaultSort = Sort.by(
                Sort.Order.asc("name").ignoreCase(),
                Sort.Order.asc("sku").ignoreCase());
        Pageable pg = PageRequest.of(
                pageable.getPageNumber(),
                pageable.getPageSize(),
                pageable.getSort().isSorted() ? pageable.getSort() : defaultSort);
        boolean catUnset = cat == null;
        Collection<String> categoryIds = List.of("");
        if (!catUnset) {
            categoryIds = resolveCategorySubtreeIds(businessId, cat, includeCategoryDescendants);
            if (categoryIds.isEmpty()) {
                return Page.empty(pg);
            }
        }
        boolean squashParentGroupsForSearch = includeAllScopes && q != null;
        String itemType = blankToNull(itemTypeId);
        boolean itemTypeUnset = itemType == null;
        Page<Item> page = itemRepository.search(
                businessId,
                q,
                bc,
                catUnset,
                categoryIds,
                noBarcode,
                includeInactive,
                includeAllScopes,
                parentsOnly,
                variantsOnly,
                skusOnly,
                blankToNull(excludeLinkedSupplierId),
                squashParentGroupsForSearch,
                itemTypeUnset,
                itemType != null ? itemType : "",
                pg);
        List<String> ids = page.getContent().stream().map(Item::getId).toList();
        Map<String, String> thumbs = firstGalleryImageUrlByItemId(ids);
        Set<String> catIds = page.getContent().stream()
                .map(Item::getCategoryId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toCollection(HashSet::new));
        Map<String, String> catNames = categoryNamesById(catIds);
        Set<String> parentIdsOnPage = page.getContent().stream()
                .filter(i -> i.getVariantOfItemId() == null)
                .map(Item::getId)
                .collect(Collectors.toCollection(HashSet::new));
        Set<String> parentsWithChildren = parentIdsOnPage.isEmpty()
                ? Set.of()
                : new HashSet<>(itemRepository.findParentIdsHavingVariants(businessId, parentIdsOnPage));
        String stockBranch = blankToNull(branchIdForStock);
        Map<String, BigDecimal> stockByItemId = Map.of();
        if (stockBranch != null) {
            branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(stockBranch, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
            if (!ids.isEmpty()) {
                stockByItemId = new HashMap<>();
                for (Object[] row : inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                        businessId, stockBranch, "active", ids)) {
                    stockByItemId.put((String) row[0], (BigDecimal) row[1]);
                }
            }
        }
        final Map<String, BigDecimal> stockMap = stockByItemId;
        final boolean includeStock = stockBranch != null;
        return page.map(item -> toSummary(
                item,
                thumbs,
                categoryNameFor(catNames, item.getCategoryId()),
                item.getVariantOfItemId() == null && parentsWithChildren.contains(item.getId()),
                includeStock ? stockMap.getOrDefault(item.getId(), BigDecimal.ZERO) : null));
    }

    @Transactional(readOnly = true)
    public ItemResponse getItem(String businessId, String itemId) {
        return getItem(businessId, itemId, null);
    }

    @Transactional(readOnly = true)
    public ItemResponse getItem(String businessId, String itemId, String branchIdForStock) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        String stockBranch = blankToNull(branchIdForStock);
        Map<String, BigDecimal> stockByItemId = branchStockMap(businessId, stockBranch, List.of(item.getId()));
        List<ItemSummaryResponse> variants = List.of();
        if (item.getVariantOfItemId() == null) {
            List<Item> variantRows = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                    businessId, item.getId());
            List<String> variantIds = variantRows.stream().map(Item::getId).toList();
            Map<String, BigDecimal> variantStock = branchStockMap(businessId, stockBranch, variantIds);
            Map<String, String> vthumbs = firstGalleryImageUrlByItemId(variantIds);
            List<Item> forCat = new ArrayList<>();
            forCat.add(item);
            forCat.addAll(variantRows);
            Map<String, String> catMap = categoryNamesById(forCat.stream().map(Item::getCategoryId).toList());
            final boolean includeStock = stockBranch != null;
            variants = variantRows.stream()
                    .map(v -> toSummary(
                            v,
                            vthumbs,
                            categoryNameFor(catMap, v.getCategoryId()),
                            false,
                            includeStock ? variantStock.getOrDefault(v.getId(), BigDecimal.ZERO) : null))
                    .toList();
        }
        BigDecimal stockQty = stockBranch != null
                ? stockByItemId.getOrDefault(item.getId(), BigDecimal.ZERO)
                : null;
        return toResponse(item, variants, stockQty);
    }

    private Map<String, BigDecimal> branchStockMap(
            String businessId,
            String stockBranch,
            Collection<String> itemIds
    ) {
        if (stockBranch == null || itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        branchRepository.findByIdAndBusinessIdAndDeletedAtIsNull(stockBranch, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Branch not found"));
        Map<String, BigDecimal> stockByItemId = new HashMap<>();
        for (Object[] row : inventoryBatchRepository.sumQuantityRemainingForItemsAtBranch(
                businessId,
                stockBranch,
                "active",
                itemIds)) {
            stockByItemId.put((String) row[0], (BigDecimal) row[1]);
        }
        return stockByItemId;
    }

    @Transactional
    public ItemCreateResult createItem(String businessId, CreateItemRequest request, String idempotencyKeyRaw) {
        if (idempotencyKeyRaw != null && !idempotencyKeyRaw.isBlank()) {
            return createItemIdempotent(businessId, request, idempotencyKeyRaw.trim());
        }
        Item item = newItemFromCreate(businessId, request);
        try {
            itemRepository.save(item);
        } catch (DataIntegrityViolationException ex) {
            throw translateDuplicateSku(ex);
        }
        supplierLinkProvisioner.afterItemChanged(businessId, item);
        return new ItemCreateResult(HttpStatus.CREATED.value(), toResponse(item, List.of(), null));
    }

    private ItemCreateResult createItemIdempotent(String businessId, CreateItemRequest request, String keyRaw) {
        String keyHash = TokenHasher.sha256Hex(keyRaw);
        synchronized ((businessId + "|" + keyHash).intern()) {
            String bodyJson;
            try {
                bodyJson = objectMapper.writeValueAsString(request);
            } catch (JsonProcessingException e) {
                throw new IllegalStateException(e);
            }
            String bodyHash = TokenHasher.sha256Hex(bodyJson);

            Optional<IdempotencyKey> existing = idempotencyKeyRepository.findByBusinessIdAndKeyHashAndRoute(
                    businessId, keyHash, ROUTE_POST_ITEMS);
            if (existing.isPresent()) {
                IdempotencyKey row = existing.get();
                if (!row.getBodyHash().equals(bodyHash)) {
                    throw new ResponseStatusException(
                            HttpStatus.CONFLICT,
                            "Idempotency key already used with a different request body"
                    );
                }
                try {
                    return new ItemCreateResult(
                            row.getHttpStatus(),
                            objectMapper.readValue(row.getResponseJson(), ItemResponse.class)
                    );
                } catch (JsonProcessingException e) {
                    throw new IllegalStateException(e);
                }
            }

            Item item = newItemFromCreate(businessId, request);
            try {
                itemRepository.saveAndFlush(item);
            } catch (DataIntegrityViolationException ex) {
                throw translateDuplicateSku(ex);
            }
            supplierLinkProvisioner.afterItemChanged(businessId, item);
            ItemResponse body = toResponse(item, List.of(), null);
            persistIdempotency(businessId, keyHash, bodyHash, HttpStatus.CREATED.value(), body);
            return new ItemCreateResult(HttpStatus.CREATED.value(), body);
        }
    }

    private void persistIdempotency(String businessId, String keyHash, String bodyHash, int httpStatus, ItemResponse body) {
        String json;
        try {
            json = objectMapper.writeValueAsString(body);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        IdempotencyKey row = new IdempotencyKey();
        row.setBusinessId(businessId);
        row.setKeyHash(keyHash);
        row.setRoute(ROUTE_POST_ITEMS);
        row.setBodyHash(bodyHash);
        row.setHttpStatus(httpStatus);
        row.setResponseJson(json);
        try {
            idempotencyKeyRepository.save(row);
        } catch (DataIntegrityViolationException e) {
            IdempotencyKey replay = idempotencyKeyRepository
                    .findByBusinessIdAndKeyHashAndRoute(businessId, keyHash, ROUTE_POST_ITEMS)
                    .orElseThrow(() -> e);
            if (!replay.getBodyHash().equals(bodyHash)) {
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Idempotency key already used with a different request body"
                );
            }
        }
    }

    @Transactional
    public ItemResponse patchItem(String businessId, String itemId, PatchItemRequest patch) {
        return patchItem(businessId, itemId, patch, null);
    }

    @Transactional
    public ItemResponse patchItem(
            String businessId,
            String itemId,
            PatchItemRequest patch,
            String actorUserId
    ) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));

        // Phase 9: Offline conflict detection — reject stale edits when the client's
        // expectedUpdatedAt does not match the server's current updatedAt.
        if (patch.expectedUpdatedAt() != null && !patch.expectedUpdatedAt().isBlank()) {
            String serverUpdatedAt = item.getUpdatedAt().toString();
            if (!serverUpdatedAt.equals(patch.expectedUpdatedAt().trim())) {
                try {
                    syncConflictService.recordConflict(
                            businessId,
                            "item",
                            itemId,
                            actorUserId,
                            java.time.Instant.parse(patch.expectedUpdatedAt().trim()).atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(),
                            item.getUpdatedAt().atOffset(java.time.ZoneOffset.UTC).toLocalDateTime(),
                            objectMapper.writeValueAsString(patch),
                            objectMapper.writeValueAsString(item)
                    );
                } catch (Exception e) {
                    log.warn("Failed to record sync conflict for item {}: {}", itemId, e.getMessage());
                }
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "Item was modified by another session. Expected updatedAt="
                                + patch.expectedUpdatedAt().trim()
                                + " but server has " + serverUpdatedAt
                                + ". Refresh and retry."
                );
            }
        }

        if (patch.sku() != null) {
            String next = patch.sku().trim();
            if (!next.isEmpty() && !next.equals(item.getSku())) {
                if (itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, next)) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU already in use");
                }
                item.setSku(next);
            }
        }
        if (patch.barcode() != null) {
            String next = normalizeBarcode(patch.barcode());
            assertBarcodeAvailable(businessId, next, item.getId());
            item.setBarcode(next);
        }
        if (patch.name() != null && !patch.name().isBlank()) {
            item.setName(patch.name().trim());
        }
        if (patch.description() != null) {
            item.setDescription(blankToNull(patch.description()));
        }
        if (patch.categoryId() != null) {
            String cid = blankToNull(patch.categoryId());
            if (cid != null) {
                categoryRepository.findByIdAndBusinessId(cid, businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category not found"));
            }
            item.setCategoryId(cid);
        }
        if (patch.aisleId() != null) {
            String aid = blankToNull(patch.aisleId());
            if (aid != null) {
                aisleRepository.findByIdAndBusinessId(aid, businessId)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aisle not found"));
            }
            item.setAisleId(aid);
        }
        if (patch.unitType() != null && !patch.unitType().isBlank()) {
            item.setUnitType(patch.unitType().trim());
        }
        if (patch.isWeighed() != null) {
            item.setWeighed(patch.isWeighed());
        }
        if (patch.isSellable() != null) {
            item.setSellable(patch.isSellable());
        }
        if (patch.isStocked() != null) {
            item.setStocked(patch.isStocked());
        }
        if (patch.packagingUnitName() != null) {
            item.setPackagingUnitName(blankToNull(patch.packagingUnitName()));
        }
        if (patch.packagingUnitQty() != null) {
            item.setPackagingUnitQty(patch.packagingUnitQty());
        }
        if (patch.bundleQty() != null) {
            item.setBundleQty(patch.bundleQty());
        }
        if (patch.bundlePrice() != null) {
            item.setBundlePrice(patch.bundlePrice());
            pricingService.syncSellingPriceFromBundle(
                    businessId, itemId, patch.bundlePrice(), actorUserId);
        }
        if (patch.buyingPrice() != null) {
            item.setBuyingPrice(patch.buyingPrice());
        }
        if (patch.bundleName() != null) {
            item.setBundleName(blankToNull(patch.bundleName()));
        }
        if (patch.minStockLevel() != null) {
            item.setMinStockLevel(patch.minStockLevel());
        }
        if (patch.reorderLevel() != null) {
            item.setReorderLevel(patch.reorderLevel());
        }
        if (patch.reorderQty() != null) {
            item.setReorderQty(patch.reorderQty());
        }
        if (patch.expiresAfterDays() != null) {
            item.setExpiresAfterDays(patch.expiresAfterDays());
        }
        if (patch.hasExpiry() != null) {
            item.setHasExpiry(patch.hasExpiry());
        }
        if (patch.imageKey() != null) {
            item.setImageKey(blankToNull(patch.imageKey()));
        }
        if (patch.active() != null) {
            item.setActive(patch.active());
        }
        if (patch.webPublished() != null) {
            item.setWebPublished(patch.webPublished());
        }
        if (patch.brand() != null) {
            item.setBrand(blankToNull(patch.brand()));
        }
        if (patch.size() != null) {
            item.setSize(blankToNull(patch.size()));
        }
        if (patch.variantName() != null && item.getVariantOfItemId() != null && !item.getVariantOfItemId().isBlank()) {
            String raw = patch.variantName().trim();
            if (raw.isEmpty()) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST, "Variant label cannot be empty");
            }
            item.setVariantName(raw);
        }

        try {
            itemRepository.save(item);
        } catch (DataIntegrityViolationException ex) {
            throw translateDuplicateSku(ex);
        }
        supplierLinkProvisioner.afterItemChanged(businessId, item);
        return toResponseWithVariants(businessId, item);
    }

    @Transactional
    public void deleteItem(String businessId, String itemId, boolean cascadeVariants) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        if (cascadeVariants && item.getVariantOfItemId() == null) {
            List<Item> children = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                    businessId, item.getId());
            for (Item child : children) {
                softDeleteItem(child);
                removeFromStockTakeChecklist(businessId, child.getId());
            }
            itemRepository.saveAll(children);
        }
        softDeleteItem(item);
        itemRepository.save(item);
        removeFromStockTakeChecklist(businessId, item.getId());
    }

    private void removeFromStockTakeChecklist(String businessId, String itemId) {
        stockTakeChecklistItemRepository.deleteByBusinessIdAndItemId(businessId, itemId);
    }

    private void softDeleteItem(Item item) {
        item.setDeletedAt(java.time.Instant.now());
        item.setBarcode(null);
        item.setActive(false);
    }

    @Transactional
    public ItemResponse createVariant(String businessId, String parentId, CreateVariantRequest request) {
        Item parent = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(parentId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent item not found"));
        if (parent.getVariantOfItemId() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot create a variant of a variant");
        }
        String rawSku = request.sku() == null ? "" : request.sku().trim();
        String sku;
        if (rawSku.isEmpty()) {
            if (parent.getSku() != null && !parent.getSku().isBlank()) {
                sku = allocateVariantSku(businessId, parent.getSku(), request.variantName());
            } else {
                // Parent is a group/label — generate standalone SKU for variant
                String cid = blankToNull(parent.getCategoryId());
                String vBrand = firstNonBlank(request.brand(), parent.getBrand());
                String vSize = firstNonBlank(request.size(), request.variantName());
                if (vBrand != null && !vBrand.isBlank() && vSize != null && !vSize.isBlank()) {
                    sku = skuGenerationService.generateSku(businessId, cid, vBrand, vSize, null);
                } else {
                    sku = peekNextStructuredParentSku(businessId, cid);
                }
            }
        } else {
            sku = rawSku;
        }
        if (itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU already in use");
        }
        String barcode = normalizeBarcode(request.barcode());
        assertBarcodeAvailable(businessId, barcode, null);

        Item child = new Item();
        child.setBusinessId(businessId);
        child.setSku(sku);
        child.setBarcode(barcode);
        child.setName(firstNonBlank(request.name(), parent.getName()));
        child.setDescription(firstNonBlank(request.description(), parent.getDescription()));
        child.setVariantOfItemId(parent.getId());
        child.setVariantName(request.variantName().trim());
        child.setItemTypeId(parent.getItemTypeId());
        child.setCategoryId(resolveOptionalCategory(businessId, request.categoryId(), parent.getCategoryId()));
        child.setAisleId(resolveOptionalAisle(businessId, request.aisleId(), parent.getAisleId()));
        child.setUnitType(firstNonBlank(request.unitType(), parent.getUnitType()));
        child.setWeighed(request.isWeighed() != null ? request.isWeighed() : parent.isWeighed());
        child.setSellable(request.isSellable() != null ? request.isSellable() : true);
        child.setStocked(request.isStocked() != null ? request.isStocked() : parent.isStocked());
        child.setPackagingUnitName(parent.getPackagingUnitName());
        child.setPackagingUnitQty(parent.getPackagingUnitQty());
        child.setBundleQty(parent.getBundleQty());
        child.setBundlePrice(parent.getBundlePrice());
        child.setBuyingPrice(parent.getBuyingPrice());
        child.setBundleName(parent.getBundleName());
        if (request.minStockLevel() != null) {
            child.setMinStockLevel(request.minStockLevel());
        }
        if (request.reorderLevel() != null) {
            child.setReorderLevel(request.reorderLevel());
        }
        if (request.reorderQty() != null) {
            child.setReorderQty(request.reorderQty());
        }
        child.setExpiresAfterDays(parent.getExpiresAfterDays());
        child.setHasExpiry(parent.isHasExpiry());
        child.setImageKey(firstNonBlank(request.imageKey(), parent.getImageKey()));
        child.setBrand(firstNonBlank(request.brand(), parent.getBrand()));
        child.setSize(firstNonBlank(request.size(), parent.getSize()));
        child.setWebPublished(true);

        try {
            itemRepository.save(child);
        } catch (DataIntegrityViolationException ex) {
            throw translateDuplicateSku(ex);
        }
        supplierLinkProvisioner.afterItemChanged(businessId, child);
        return toResponse(child, List.of(), null);
    }

    @Transactional
    public ItemImageResponse registerItemImage(String businessId, String itemId, RegisterItemImageRequest request) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        String legacyKey = blankToNull(request.s3Key());
        String pubId = blankToNull(request.cloudinaryPublicId());
        String secure = blankToNull(request.secureUrl());
        boolean cloudinaryMeta = pubId != null && secure != null;
        if (!cloudinaryMeta && legacyKey == null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Provide s3Key (legacy storage) or both secureUrl and cloudinaryPublicId"
            );
        }
        if (cloudinaryMeta && legacyKey != null) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Use either legacy s3Key or Cloudinary fields, not both"
            );
        }
        int sortOrder = request.sortOrder() != null
                ? request.sortOrder()
                : itemImageRepository.maxSortOrderForItem(itemId) + 1;
        ItemImage img = new ItemImage();
        img.setItemId(itemId);
        img.setSortOrder(sortOrder);
        img.setWidth(request.width());
        img.setHeight(request.height());
        img.setContentType(blankToNull(request.contentType()));
        img.setAltText(blankToNull(request.altText()));
        if (cloudinaryMeta) {
            img.setProvider(ItemImageStorageProvider.CLOUDINARY);
            img.setCloudinaryPublicId(pubId);
            img.setSecureUrl(secure);
            img.setS3Key(pubId);
            img.setBytes(request.bytes());
            img.setFormat(blankToNull(request.format()));
            img.setAssetSignature(blankToNull(request.assetSignature()));
            img.setPredominantColorHex(normalizeHex(blankToNull(request.predominantColorHex())));
            img.setPhash(blankToNull(request.phash()));
        } else {
            img.setProvider(ItemImageStorageProvider.LEGACY);
            img.setS3Key(legacyKey);
        }
        itemImageRepository.save(img);
        if (Boolean.TRUE.equals(request.primary())) {
            String cover = cloudinaryMeta ? secure : legacyKey;
            item.setImageKey(cover);
            itemRepository.save(item);
        }
        return toImageResponse(img);
    }

    @Transactional
    public ItemImageResponse uploadItemImageCloudinary(
            String businessId,
            String itemId,
            byte[] bytes,
            String originalFilename,
            String altText,
            boolean primary
    ) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        CloudinaryUploadResult r = cloudinaryImageService.uploadImage(bytes, originalFilename, businessId, itemId);
        int sortOrder = itemImageRepository.maxSortOrderForItem(itemId) + 1;
        ItemImage img = new ItemImage();
        img.setItemId(itemId);
        img.setProvider(ItemImageStorageProvider.CLOUDINARY);
        img.setCloudinaryPublicId(r.publicId());
        img.setSecureUrl(r.secureUrl());
        img.setS3Key(r.publicId());
        img.setWidth(r.width());
        img.setHeight(r.height());
        img.setBytes(r.bytes());
        img.setFormat(r.format());
        img.setContentType(r.contentType());
        img.setAssetSignature(r.versionSignature());
        img.setPredominantColorHex(normalizeHex(r.predominantColorHex()));
        img.setPhash(r.phash());
        img.setAltText(blankToNull(altText));
        img.setSortOrder(sortOrder);
        itemImageRepository.save(img);
        if (primary) {
            item.setImageKey(r.secureUrl());
            itemRepository.save(item);
        }
        return toImageResponse(img);
    }

    @Transactional
    public void deleteItemImage(String businessId, String itemId, String imageId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        ItemImage img = itemImageRepository.findByIdAndItemId(imageId, itemId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Image not found"));
        String matchUrl = img.getSecureUrl();
        String matchKey = img.getS3Key();
        if (ItemImageStorageProvider.CLOUDINARY.equals(img.getProvider())) {
            String pid = img.getCloudinaryPublicId();
            if (pid != null && !pid.isBlank() && cloudinaryImageService.isConfigured()) {
                try {
                    cloudinaryImageService.destroyImage(pid);
                } catch (Exception ex) {
                    log.warn("Cloudinary destroy failed for public_id={}: {}", pid, ex.toString());
                }
            }
        }
        itemImageRepository.delete(img);
        boolean matchesPrimary = item.getImageKey() != null
                && ((matchUrl != null && matchUrl.equals(item.getImageKey()))
                || (matchKey != null && matchKey.equals(item.getImageKey())));
        if (matchesPrimary) {
            item.setImageKey(null);
            itemRepository.save(item);
        }
    }

    /**
     * Performance harness for Slice 5 DoD (1k rows in one transaction).
     */
    @Transactional
    public void bulkCreateSimpleItemsForPerfTest(String businessId, String itemTypeId, int count) {
        itemTypeRepository.findByIdAndBusinessId(itemTypeId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item type not found"));
        List<Item> batch = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            Item it = new Item();
            it.setBusinessId(businessId);
            it.setSku("perf-" + i);
            it.setName("Perf Item " + i);
            it.setItemTypeId(itemTypeId);
            it.setUnitType("each");
            batch.add(it);
        }
        itemRepository.saveAll(batch);
    }

    @Transactional(readOnly = true)
    public String suggestNextSku(
            String businessId,
            String categoryIdRaw,
            String parentItemIdRaw,
            String variantNameRaw,
            String brand,
            String size
    ) {
        String pid = blankToNull(parentItemIdRaw);
        if (pid != null) {
            Item parent = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(pid, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parent item not found"));
            if (parent.getVariantOfItemId() != null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "parentItemId must be a parent product");
            }
            if (parent.getSku() != null && !parent.getSku().isBlank()) {
                String variantSku = skuGenerationService.generateVariantSku(businessId, parent.getSku(), variantNameRaw);
                if (variantSku != null) {
                    return variantSku;
                }
                return firstAvailableVariantSku(businessId, parent.getSku(), variantNameRaw);
            }
            // Parent has no SKU (group/label) — generate standalone SKU for variant
            String cid = blankToNull(parent.getCategoryId());
            String vBrand = firstNonBlank(brand, parent.getBrand());
            String vSize = firstNonBlank(size, variantNameRaw);
            if (vBrand != null && !vBrand.isBlank() && vSize != null && !vSize.isBlank()) {
                String structured = skuGenerationService.generateSku(businessId, cid, vBrand, vSize, null);
                if (structured != null) {
                    return structured;
                }
            }
            return peekNextStructuredParentSku(businessId, cid);
        }
        String cid = blankToNull(categoryIdRaw);
        String structuredSku = skuGenerationService.generateSku(businessId, cid, brand, size, null);
        if (structuredSku != null) {
            return structuredSku;
        }
        return peekNextStructuredParentSku(businessId, cid);
    }

    private static final long STRUCTURED_SEQ_START = 10_001L;
    private static final int MAX_CATEGORY_PREFIX_CHARS = 6;
    private static final int MAX_VARIANT_SEGMENT_CHARS = 20;
    private static final int MAX_SKU_LEN = 191;
    private static final int MAX_VARIANT_SKU_TARGET = 180;

    private String peekNextStructuredParentSku(String businessId, String categoryId) {
        String prefix = categoryPrefixFromCategoryId(businessId, categoryId);
        return nextSequenceSkuForPrefix(businessId, prefix, false);
    }

    private String allocateStructuredParentSku(String businessId, String categoryId) {
        String prefix = categoryPrefixFromCategoryId(businessId, categoryId);
        return nextSequenceSkuForPrefix(businessId, prefix, true);
    }

    private String categoryPrefixFromCategoryId(String businessId, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return "SKU";
        }
        return categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                .map(c -> slugToCategoryPrefix(c.getSlug()))
                .orElse("SKU");
    }

    private static String slugToCategoryPrefix(String slug) {
        if (slug == null || slug.isBlank()) {
            return "SKU";
        }
        String u = slug.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (u.length() < 3) {
            u = ("CAT" + u).toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        }
        if (u.isEmpty()) {
            return "SKU";
        }
        return u.length() > MAX_CATEGORY_PREFIX_CHARS
                ? u.substring(0, MAX_CATEGORY_PREFIX_CHARS)
                : u;
    }

    private String nextSequenceSkuForPrefix(String businessId, String prefix, boolean claim) {
        String pfx = prefix.toUpperCase(Locale.ROOT) + "-";
        long max = 0;
        boolean any = false;
        for (String sku : itemRepository.findSkusByBusinessIdActive(businessId)) {
            if (sku == null || sku.isBlank() || !sku.startsWith(pfx)) {
                continue;
            }
            String tail = sku.substring(pfx.length());
            if (!isAllAsciiDigits(tail)) {
                continue;
            }
            try {
                long v = Long.parseLong(tail);
                if (v > max) {
                    max = v;
                }
                any = true;
            } catch (NumberFormatException ignored) {
                // skip
            }
        }
        long candidate = any ? max + 1 : STRUCTURED_SEQ_START;
        if (candidate < STRUCTURED_SEQ_START) {
            candidate = STRUCTURED_SEQ_START;
        }
        if (!claim) {
            return pfx + candidate;
        }
        int guard = 0;
        while (itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, pfx + candidate)) {
            candidate++;
            if (++guard > 10_000) {
                throw new ResponseStatusException(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "Could not allocate a unique SKU for prefix " + prefix
                );
            }
        }
        return pfx + candidate;
    }

    private String firstAvailableVariantSku(String businessId, String parentSku, String variantNameRaw) {
        String segment = variantNameToSkuSegment(variantNameRaw);
        String base = buildVariantSkuBase(parentSku, segment);
        for (int i = 0; i < 5000; i++) {
            String candidate = i == 0 ? base : base + "-" + (i + 1);
            if (candidate.length() > MAX_SKU_LEN) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Variant SKU would exceed max length; shorten the parent SKU or the option label."
                );
            }
            if (!itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, candidate)) {
                return candidate;
            }
        }
        throw new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Could not allocate a unique variant SKU"
        );
    }

    private String allocateVariantSku(String businessId, String parentSku, String variantName) {
        String structured = skuGenerationService.generateVariantSku(businessId, parentSku, variantName);
        if (structured != null) {
            return structured;
        }
        return firstAvailableVariantSku(businessId, parentSku, variantName);
    }

    private static String variantNameToSkuSegment(String variantName) {
        if (variantName == null || variantName.isBlank()) {
            return "OPT";
        }
        String s = variantName.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "-");
        s = s.replaceAll("-+", "-");
        if (s.startsWith("-")) {
            s = s.substring(1);
        }
        if (s.endsWith("-")) {
            s = s.substring(0, s.length() - 1);
        }
        if (s.isBlank()) {
            return "OPT";
        }
        if (s.length() > MAX_VARIANT_SEGMENT_CHARS) {
            return s.substring(0, MAX_VARIANT_SEGMENT_CHARS);
        }
        return s;
    }

    private static String buildVariantSkuBase(String parentSku, String segment) {
        String ps = parentSku == null ? "" : parentSku.trim();
        if (ps.isEmpty()) {
            ps = "PARENT";
        }
        String sep = "-";
        String candidate = ps + sep + segment;
        if (candidate.length() <= MAX_VARIANT_SKU_TARGET) {
            return candidate;
        }
        int budget = MAX_VARIANT_SKU_TARGET - sep.length() - segment.length();
        if (budget < 4) {
            budget = 4;
        }
        String p = ps.length() <= budget ? ps : ps.substring(0, budget);
        return p + sep + segment;
    }

    private static boolean isAllAsciiDigits(String s) {
        if (s.isEmpty()) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private Item newItemFromCreate(String businessId, CreateItemRequest request) {
        String itemTypeId = request.itemTypeId().trim();
        itemTypeRepository.findByIdAndBusinessId(itemTypeId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item type not found"));

        String categoryId = blankToNull(request.categoryId());
        if (categoryId != null) {
            categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category not found"));
        }

        String rawSku = request.sku() == null ? "" : request.sku().trim();
        String sku;
        if (rawSku.isEmpty()) {
            String generated = skuGenerationService.generateSku(businessId, categoryId, request.brand(), request.size(), null);
            sku = generated != null ? generated : allocateStructuredParentSku(businessId, categoryId);
        } else {
            sku = rawSku;
        }
        if (itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU already in use");
        }
        String barcode = normalizeBarcode(request.barcode());
        assertBarcodeAvailable(businessId, barcode, null);

        String aisleId = blankToNull(request.aisleId());
        if (aisleId != null) {
            aisleRepository.findByIdAndBusinessId(aisleId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aisle not found"));
        }

        Item item = new Item();
        item.setBusinessId(businessId);
        item.setSku(sku);
        item.setBarcode(barcode);
        item.setName(request.name().trim());
        item.setDescription(blankToNull(request.description()));
        item.setItemTypeId(itemTypeId);
        item.setCategoryId(categoryId);
        item.setAisleId(aisleId);
        item.setUnitType(request.unitType() != null && !request.unitType().isBlank()
                ? request.unitType().trim()
                : "each");
        item.setWeighed(Boolean.TRUE.equals(request.isWeighed()));
        item.setSellable(request.isSellable() == null || request.isSellable());
        item.setStocked(request.isStocked() == null || request.isStocked());
        item.setPackagingUnitName(blankToNull(request.packagingUnitName()));
        item.setPackagingUnitQty(request.packagingUnitQty());
        item.setBundleQty(request.bundleQty());
        item.setBundlePrice(request.bundlePrice());
        item.setBuyingPrice(request.buyingPrice());
        item.setBundleName(blankToNull(request.bundleName()));
        item.setMinStockLevel(request.minStockLevel());
        item.setReorderLevel(request.reorderLevel());
        item.setReorderQty(request.reorderQty());
        item.setExpiresAfterDays(request.expiresAfterDays());
        item.setHasExpiry(Boolean.TRUE.equals(request.hasExpiry()));
        item.setImageKey(blankToNull(request.imageKey()));
        item.setBrand(blankToNull(request.brand()));
        item.setSize(blankToNull(request.size()));
        item.setWebPublished(true);
        return item;
    }

    private ItemResponse toResponseWithVariants(String businessId, Item item) {
        List<ItemSummaryResponse> variants = List.of();
        if (item.getVariantOfItemId() == null) {
            List<Item> variantRows = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                    businessId, item.getId());
            List<String> variantIds = variantRows.stream().map(Item::getId).toList();
            Map<String, String> vthumbs = firstGalleryImageUrlByItemId(variantIds);
            List<Item> forCat = new ArrayList<>();
            forCat.add(item);
            forCat.addAll(variantRows);
            Map<String, String> catMap = categoryNamesById(forCat.stream().map(Item::getCategoryId).toList());
            variants = variantRows.stream()
                    .map(v -> toSummary(v, vthumbs, categoryNameFor(catMap, v.getCategoryId()), false, null))
                    .toList();
        }
        return toResponse(item, variants, null);
    }

    private Map<String, String> firstGalleryImageUrlByItemId(Collection<String> itemIds) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        Sort galleryOrder = Sort.by(Sort.Order.asc("itemId"), Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
        List<ItemImage> rows = itemImageRepository.findByItemIdIn(itemIds, galleryOrder);
        Map<String, String> out = new LinkedHashMap<>();
        for (ItemImage img : rows) {
            String url = resolveImageRowPublicUrl(img);
            if (url == null) {
                continue;
            }
            out.putIfAbsent(img.getItemId(), url);
        }
        return out;
    }

    private static String resolveImageRowPublicUrl(ItemImage img) {
        String secure = img.getSecureUrl();
        if (secure != null && !secure.isBlank()) {
            return secure.trim();
        }
        String key = img.getS3Key();
        if (key != null) {
            String k = key.trim();
            if (k.startsWith("http://") || k.startsWith("https://")) {
                return k;
            }
        }
        return null;
    }

    private static String resolveListThumbnail(Item i, Map<String, String> galleryFirstUrlByItemId) {
        String k = i.getImageKey();
        if (k != null && (k.startsWith("http://") || k.startsWith("https://"))) {
            return k.trim();
        }
        return galleryFirstUrlByItemId.get(i.getId());
    }

    private Map<String, String> categoryNamesById(Collection<String> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return Map.of();
        }
        List<String> distinct = categoryIds.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .distinct()
                .toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        categoryRepository.findAllById(distinct).forEach(c -> out.put(c.getId(), c.getName()));
        return out;
    }

    private static String categoryNameFor(Map<String, String> namesById, String categoryId) {
        if (categoryId == null || categoryId.isBlank()) {
            return null;
        }
        return namesById.get(categoryId);
    }

    private ItemSummaryResponse toSummary(
            Item i,
            Map<String, String> galleryFirstUrlByItemId,
            String categoryName,
            boolean groupLabelOnly,
            BigDecimal stockQty
    ) {
        return new ItemSummaryResponse(
                i.getId(),
                i.getSku(),
                i.getBarcode(),
                i.getName(),
                i.getVariantName(),
                i.getCategoryId(),
                categoryName,
                i.getImageKey(),
                resolveListThumbnail(i, galleryFirstUrlByItemId),
                i.isActive(),
                i.isWebPublished(),
                i.getVariantOfItemId(),
                groupLabelOnly,
                stockQty,
                i.getBrand(),
                i.getSize()
        );
    }

    private ItemResponse toResponse(Item i, List<ItemSummaryResponse> variants, BigDecimal stockQty) {
        return new ItemResponse(
                i.getId(),
                i.getSku(),
                i.getBarcode(),
                i.getName(),
                i.getDescription(),
                i.getVariantOfItemId(),
                i.getVariantName(),
                i.getCategoryId(),
                i.getAisleId(),
                i.getItemTypeId(),
                i.getUnitType(),
                i.isWeighed(),
                i.isSellable(),
                i.isStocked(),
                i.getCurrentStock(),
                i.getPackagingUnitName(),
                i.getPackagingUnitQty(),
                i.getBundleQty(),
                i.getBundlePrice(),
                i.getBuyingPrice(),
                i.getBundleName(),
                i.getMinStockLevel(),
                i.getReorderLevel(),
                i.getReorderQty(),
                i.getExpiresAfterDays(),
                i.isHasExpiry(),
                i.getImageKey(),
                i.isActive(),
                i.isWebPublished(),
                i.getVersion(),
                loadImageResponses(i.getId()),
                variants,
                i.getBrand(),
                i.getSize(),
                stockQty
        );
    }

    private List<ItemImageResponse> loadImageResponses(String itemId) {
        return itemImageRepository.findByItemIdOrderBySortOrderAscIdAsc(itemId).stream()
                .map(this::toImageResponse)
                .toList();
    }

    private ItemImageResponse toImageResponse(ItemImage img) {
        return new ItemImageResponse(
                img.getId(),
                img.getS3Key(),
                img.getSecureUrl(),
                img.getCloudinaryPublicId(),
                img.getProvider(),
                img.getWidth(),
                img.getHeight(),
                img.getSortOrder(),
                img.getContentType(),
                img.getAltText(),
                img.getBytes(),
                img.getFormat(),
                img.getAssetSignature(),
                img.getPredominantColorHex(),
                img.getPhash(),
                img.getCreatedAt()
        );
    }

    private List<String> resolveCategorySubtreeIds(String businessId, String categoryId, boolean includeDescendants) {
        if (categoryRepository.findByIdAndBusinessId(categoryId, businessId).isEmpty()) {
            return List.of();
        }
        if (!includeDescendants) {
            return List.of(categoryId);
        }
        List<Category> all = categoryRepository.findByBusinessIdOrderByPositionAsc(businessId);
        Map<String, List<String>> childrenByParent = new LinkedHashMap<>();
        for (Category c : all) {
            String pk = c.getParentId() == null ? "" : c.getParentId();
            childrenByParent.computeIfAbsent(pk, k -> new ArrayList<>()).add(c.getId());
        }
        List<String> out = new ArrayList<>();
        ArrayDeque<String> dq = new ArrayDeque<>();
        dq.add(categoryId);
        while (!dq.isEmpty()) {
            String cur = dq.poll();
            out.add(cur);
            for (String child : childrenByParent.getOrDefault(cur, List.of())) {
                dq.add(child);
            }
        }
        return out;
    }

    private static String normalizeHex(String hex) {
        if (hex == null || hex.isBlank()) {
            return null;
        }
        String t = hex.trim();
        if (t.startsWith("#")) {
            return t.length() > 9 ? t.substring(0, 7) : t;
        }
        return "#" + t.replace("#", "");
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private static String firstNonBlank(String preferred, String fallback) {
        if (preferred != null && !preferred.isBlank()) {
            return preferred.trim();
        }
        return fallback;
    }

    private static String normalizeBarcode(String barcode) {
        if (barcode == null || barcode.isBlank()) {
            return null;
        }
        return barcode.trim();
    }

    private void assertBarcodeAvailable(String businessId, String barcode, String ignoreItemId) {
        if (barcode == null) {
            return;
        }
        itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(businessId, barcode)
                .filter(other -> ignoreItemId == null || !other.getId().equals(ignoreItemId))
                .ifPresent(other -> {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Barcode already in use");
                });
    }

    private static ResponseStatusException translateDuplicateSku(DataIntegrityViolationException ex) {
        String m = String.valueOf(ex.getMostSpecificCause().getMessage()) + " " + ex.getMessage();
        if (m.contains("uq_items_business_sku") || m.toLowerCase().contains("business_sku")) {
            return new ResponseStatusException(HttpStatus.CONFLICT, "SKU already in use");
        }
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not save item", ex);
    }

    private String resolveOptionalCategory(String businessId, String requestId, String inherited) {
        if (requestId == null) {
            return inherited;
        }
        String cid = blankToNull(requestId);
        if (cid == null) {
            return null;
        }
        categoryRepository.findByIdAndBusinessId(cid, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category not found"));
        return cid;
    }

    private String resolveOptionalAisle(String businessId, String requestId, String inherited) {
        if (requestId == null) {
            return inherited;
        }
        String aid = blankToNull(requestId);
        if (aid == null) {
            return null;
        }
        aisleRepository.findByIdAndBusinessId(aid, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Aisle not found"));
        return aid;
    }
}
