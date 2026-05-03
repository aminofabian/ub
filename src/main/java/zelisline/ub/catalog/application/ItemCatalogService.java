package zelisline.ub.catalog.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

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
import zelisline.ub.catalog.api.dto.CreateItemRequest;
import zelisline.ub.catalog.api.dto.CreateVariantRequest;
import zelisline.ub.catalog.api.dto.ItemImageResponse;
import zelisline.ub.catalog.api.dto.ItemResponse;
import zelisline.ub.catalog.api.dto.ItemSummaryResponse;
import zelisline.ub.catalog.api.dto.PatchItemRequest;
import zelisline.ub.catalog.api.dto.RegisterItemImageRequest;
import zelisline.ub.catalog.domain.IdempotencyKey;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.repository.AisleRepository;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.IdempotencyKeyRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.identity.application.TokenHasher;
import zelisline.ub.suppliers.application.SupplierLinkProvisioner;

@Service
@RequiredArgsConstructor
public class ItemCatalogService {

    public static final String ROUTE_POST_ITEMS = "POST /api/v1/items";

    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final CategoryRepository categoryRepository;
    private final AisleRepository aisleRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final IdempotencyKeyRepository idempotencyKeyRepository;
    private final ObjectMapper objectMapper;
    private final SupplierLinkProvisioner supplierLinkProvisioner;

    @Transactional(readOnly = true)
    public Page<ItemSummaryResponse> listItems(
            String businessId,
            String search,
            String barcodeExact,
            String categoryId,
            boolean noBarcode,
            boolean includeInactive,
            Pageable pageable
    ) {
        String q = blankToNull(search);
        String bc = blankToNull(barcodeExact);
        String cat = blankToNull(categoryId);
        Pageable pg = pageable.getSort().isUnsorted()
                ? PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), Sort.by(Sort.Direction.ASC, "name"))
                : pageable;
        return itemRepository.search(businessId, q, bc, cat, noBarcode, includeInactive, pg)
                .map(this::toSummary);
    }

    @Transactional(readOnly = true)
    public ItemResponse getItem(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        List<ItemSummaryResponse> variants = List.of();
        if (item.getVariantOfItemId() == null) {
            variants = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(businessId, item.getId())
                    .stream()
                    .map(this::toSummary)
                    .toList();
        }
        return toResponse(item, variants);
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
        return new ItemCreateResult(HttpStatus.CREATED.value(), toResponse(item, List.of()));
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
            ItemResponse body = toResponse(item, List.of());
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
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));

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
            }
            itemRepository.saveAll(children);
        }
        softDeleteItem(item);
        itemRepository.save(item);
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
        String sku = request.sku().trim();
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
        child.setSellable(request.isSellable() != null ? request.isSellable() : parent.isSellable());
        child.setStocked(request.isStocked() != null ? request.isStocked() : parent.isStocked());
        child.setPackagingUnitName(parent.getPackagingUnitName());
        child.setPackagingUnitQty(parent.getPackagingUnitQty());
        child.setBundleQty(parent.getBundleQty());
        child.setBundlePrice(parent.getBundlePrice());
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

        try {
            itemRepository.save(child);
        } catch (DataIntegrityViolationException ex) {
            throw translateDuplicateSku(ex);
        }
        supplierLinkProvisioner.afterItemChanged(businessId, child);
        return toResponse(child, List.of());
    }

    @Transactional
    public ItemImageResponse registerItemImage(String businessId, String itemId, RegisterItemImageRequest request) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Item not found"));
        int sortOrder = request.sortOrder() != null
                ? request.sortOrder()
                : itemImageRepository.maxSortOrderForItem(itemId) + 1;
        ItemImage img = new ItemImage();
        img.setItemId(itemId);
        img.setS3Key(request.s3Key().trim());
        img.setWidth(request.width());
        img.setHeight(request.height());
        img.setSortOrder(sortOrder);
        img.setContentType(blankToNull(request.contentType()));
        img.setAltText(blankToNull(request.altText()));
        itemImageRepository.save(img);
        if (Boolean.TRUE.equals(request.primary())) {
            item.setImageKey(img.getS3Key());
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
        String key = img.getS3Key();
        itemImageRepository.delete(img);
        if (key != null && key.equals(item.getImageKey())) {
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

    private Item newItemFromCreate(String businessId, CreateItemRequest request) {
        String sku = request.sku().trim();
        if (itemRepository.existsByBusinessIdAndSkuAndDeletedAtIsNull(businessId, sku)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "SKU already in use");
        }
        String barcode = normalizeBarcode(request.barcode());
        assertBarcodeAvailable(businessId, barcode, null);

        String itemTypeId = request.itemTypeId().trim();
        itemTypeRepository.findByIdAndBusinessId(itemTypeId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item type not found"));

        String categoryId = blankToNull(request.categoryId());
        if (categoryId != null) {
            categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Category not found"));
        }
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
        item.setBundleName(blankToNull(request.bundleName()));
        item.setMinStockLevel(request.minStockLevel());
        item.setReorderLevel(request.reorderLevel());
        item.setReorderQty(request.reorderQty());
        item.setExpiresAfterDays(request.expiresAfterDays());
        item.setHasExpiry(Boolean.TRUE.equals(request.hasExpiry()));
        item.setImageKey(blankToNull(request.imageKey()));
        return item;
    }

    private ItemResponse toResponseWithVariants(String businessId, Item item) {
        List<ItemSummaryResponse> variants = List.of();
        if (item.getVariantOfItemId() == null) {
            variants = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(businessId, item.getId())
                    .stream()
                    .map(this::toSummary)
                    .toList();
        }
        return toResponse(item, variants);
    }

    private ItemSummaryResponse toSummary(Item i) {
        return new ItemSummaryResponse(
                i.getId(),
                i.getSku(),
                i.getBarcode(),
                i.getName(),
                i.getVariantName(),
                i.getCategoryId(),
                i.getImageKey(),
                i.isActive(),
                i.getVariantOfItemId()
        );
    }

    private ItemResponse toResponse(Item i, List<ItemSummaryResponse> variants) {
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
                i.getPackagingUnitName(),
                i.getPackagingUnitQty(),
                i.getBundleQty(),
                i.getBundlePrice(),
                i.getBundleName(),
                i.getMinStockLevel(),
                i.getReorderLevel(),
                i.getReorderQty(),
                i.getExpiresAfterDays(),
                i.isHasExpiry(),
                i.getImageKey(),
                i.isActive(),
                i.getVersion(),
                loadImageResponses(i.getId()),
                variants
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
                img.getWidth(),
                img.getHeight(),
                img.getSortOrder(),
                img.getContentType(),
                img.getAltText(),
                img.getCreatedAt()
        );
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
