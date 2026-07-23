package zelisline.ub.globalcatalog.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.audit.AuditEventTypes;
import zelisline.ub.audit.application.AuditEventBuilder;
import zelisline.ub.audit.application.AuditEventPublisher;
import zelisline.ub.audit.domain.AuditEventActorType;
import zelisline.ub.audit.domain.AuditEventCategory;
import zelisline.ub.audit.domain.AuditEventSeverity;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteLineResult;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteRequest;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.PromoteResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.SourceBusinessResponse;
import zelisline.ub.globalcatalog.api.dto.SuperAdminGlobalCatalogDtos.SourceItemResponse;
import zelisline.ub.globalcatalog.domain.GlobalCatalog;
import zelisline.ub.globalcatalog.domain.GlobalCategory;
import zelisline.ub.globalcatalog.domain.GlobalProduct;
import zelisline.ub.globalcatalog.domain.GlobalProductStatus;
import zelisline.ub.globalcatalog.repository.GlobalCatalogRepository;
import zelisline.ub.globalcatalog.repository.GlobalCategoryRepository;
import zelisline.ub.globalcatalog.repository.GlobalProductRepository;
import zelisline.ub.platform.media.CloudinaryImageService;
import zelisline.ub.platform.media.CloudinaryUploadResult;
import zelisline.ub.platform.media.MediaStore;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Promotes tenant items into the platform global catalog without impersonation.
 * Always scopes item loads by {@code sourceBusinessId}.
 */
@Service
@RequiredArgsConstructor
public class SuperAdminGlobalCatalogPromoteService {

    private static final Logger log = LoggerFactory.getLogger(SuperAdminGlobalCatalogPromoteService.class);

    /** Live progress hook for async promote jobs (called once per processed line). */
    public interface PromoteProgressListener {
        void onItemProcessed(int processedCount, String itemName);
    }

    private static final String DEFAULT_CATALOG_CODE = "default";
    private static final int MAX_BATCH = 100;
    private static final int MAX_JOB_BATCH = 500;
    private static final String PREFERRED_SOURCE_BUSINESS_ID = "7fcae206-6c1c-4c32-81a2-109fe0015042";
    private static final String PREFERRED_SOURCE_SLUG = "palmart";

    private final BusinessRepository businessRepository;
    private final ItemRepository itemRepository;
    private final ItemImageRepository itemImageRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final CategoryRepository categoryRepository;
    private final SellingPriceRepository sellingPriceRepository;
    private final GlobalCatalogRepository globalCatalogRepository;
    private final GlobalCategoryRepository globalCategoryRepository;
    private final GlobalProductRepository globalProductRepository;
    private final MediaStore mediaStore;
    private final AuditEventPublisher auditEventPublisher;
    private final AuditEventBuilder auditEventBuilder;

    @Transactional(readOnly = true)
    public List<SourceBusinessResponse> listSourceBusinesses() {
        List<Business> businesses = businessRepository.findAll().stream()
                .filter(b -> b.getDeletedAt() == null)
                .sorted((a, b) -> {
                    int pref = Boolean.compare(isPreferred(b), isPreferred(a));
                    if (pref != 0) {
                        return pref;
                    }
                    return a.getName().compareToIgnoreCase(b.getName());
                })
                .toList();
        return businesses.stream()
                .map(b -> new SourceBusinessResponse(b.getId(), b.getName(), b.getSlug(), isPreferred(b)))
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<SourceItemResponse> listSourceItems(
            String businessId,
            String catalogId,
            String q,
            int page,
            int size
    ) {
        requireBusiness(businessId);
        GlobalCatalog catalog = requireCatalog(catalogId);
        GlobalMatchIndex matchIndex = GlobalMatchIndex.build(
                globalProductRepository.findAll().stream()
                        .filter(p -> catalog.getId().equals(p.getCatalogId()))
                        .filter(p -> !GlobalProductStatus.ARCHIVED.equals(p.getStatus()))
                        .toList());

        PageRequest pageable = PageRequest.of(
                Math.max(page, 0),
                Math.min(Math.max(size, 1), 100),
                Sort.by(Sort.Order.asc("name")));
        return itemRepository.searchActiveByBusiness(businessId, blankToNull(q), pageable)
                .map(item -> {
                    String matchedId = matchIndex.findMatchId(item);
                    return new SourceItemResponse(
                            item.getId(),
                            item.getSku(),
                            item.getName(),
                            item.getBrand(),
                            item.getSize(),
                            item.getBarcode(),
                            resolveHttpsImageUrl(item),
                            matchedId != null,
                            matchedId);
                });
    }

    @Transactional(readOnly = true)
    public PromoteResponse preview(PromoteRequest request) {
        return runPromote(request, true, MAX_BATCH, null);
    }

    @Transactional
    public PromoteResponse promote(PromoteRequest request) {
        PromoteResponse result = runPromote(request, false, MAX_BATCH, null);
        publishAudit(request, result, currentSuperAdminId());
        return result;
    }

    /**
     * Async job path: larger batch ceiling; actor id comes from the job row
     * (scheduler has no SecurityContext). Reports per-line progress so the
     * SA UI can render a live progress bar while images re-host.
     */
    @Transactional
    public PromoteResponse promoteForJob(
            PromoteRequest request,
            String actorUserId,
            PromoteProgressListener progressListener
    ) {
        PromoteResponse result = runPromote(request, false, MAX_JOB_BATCH, progressListener);
        publishAudit(request, result, actorUserId);
        return result;
    }

    private PromoteResponse runPromote(
            PromoteRequest request,
            boolean dryRun,
            int maxBatch,
            PromoteProgressListener progressListener
    ) {
        if (request.itemIds().size() > maxBatch) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "Promote batch limited to " + maxBatch + " items");
        }
        String businessId = request.sourceBusinessId().trim();
        requireBusiness(businessId);
        String onConflict = normalizeOnConflict(request.onConflict());
        boolean publish = Boolean.TRUE.equals(request.publish());
        String targetStatus = publish ? GlobalProductStatus.PUBLISHED : GlobalProductStatus.DRAFT;

        GlobalCatalog catalog = requireCatalog(request.catalogId());
        List<Item> items = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(
                new HashSet<>(request.itemIds()), businessId);
        Map<String, Item> byId = new HashMap<>();
        for (Item item : items) {
            byId.put(item.getId(), item);
        }

        Map<String, BigDecimal> sellingByItem = loadSellingPrices(businessId, byId.keySet());
        Map<String, ItemType> itemTypes = loadItemTypes(businessId);
        Map<String, Category> categories = loadCategories(businessId, byId.values());

        GlobalMatchIndex matchIndex = GlobalMatchIndex.build(
                globalProductRepository.findAll().stream()
                        .filter(p -> catalog.getId().equals(p.getCatalogId()))
                        .filter(p -> !GlobalProductStatus.ARCHIVED.equals(p.getStatus()))
                        .toList());

        int created = 0;
        int updated = 0;
        int skipped = 0;
        int imageRehosts = 0;
        List<PromoteLineResult> lines = new ArrayList<>();

        // Preserve request order
        int reachedCount = 0;
        for (String itemId : request.itemIds()) {
            Item item = byId.get(itemId);
            if (progressListener != null) {
                progressListener.onItemProcessed(reachedCount, item != null ? item.getName() : null);
            }
            reachedCount++;
            if (item == null) {
                skipped++;
                lines.add(new PromoteLineResult(itemId, null, "skipped", "Item not found in source business", false));
                continue;
            }

            GlobalProduct existing = matchIndex.findMatch(item);
            if (existing != null && "skip".equals(onConflict)) {
                skipped++;
                lines.add(new PromoteLineResult(
                        itemId, existing.getId(), "skipped", "Already in global (onConflict=skip)", false));
                continue;
            }

            String sourceImageUrl = resolveHttpsImageUrl(item);
            if (dryRun) {
                String action = existing == null ? "create" : "update";
                boolean wouldRehost = sourceImageUrl != null;
                if ("create".equals(action)) {
                    created++;
                } else {
                    updated++;
                }
                if (wouldRehost) {
                    imageRehosts++;
                }
                lines.add(new PromoteLineResult(
                        itemId,
                        existing == null ? null : existing.getId(),
                        action,
                        wouldRehost ? "Will re-host image" : "No portable source image",
                        wouldRehost));
                continue;
            }

            try {
                GlobalProduct product = existing != null ? existing : new GlobalProduct();
                if (existing == null) {
                    product.setCatalogId(catalog.getId());
                    // Prefer keeping seed/legacy id alignment when the tenant item id is free
                    if (!globalProductRepository.existsById(item.getId())) {
                        product.setId(item.getId());
                    }
                }
                applyItemFields(
                        product,
                        item,
                        targetStatus,
                        sellingByItem.get(item.getId()),
                        itemTypes.get(item.getItemTypeId()),
                        categories.get(item.getCategoryId()),
                        catalog.getId());

                if (blankToNull(product.getBarcode()) != null
                        && !GlobalProductStatus.ARCHIVED.equals(product.getStatus())) {
                    long conflicts = existing == null
                            ? globalProductRepository.countByCatalogIdAndBarcodeAndStatusNot(
                                    catalog.getId(), product.getBarcode(), GlobalProductStatus.ARCHIVED)
                            : globalProductRepository.countByCatalogIdAndBarcodeAndStatusNotAndIdNot(
                                    catalog.getId(),
                                    product.getBarcode(),
                                    GlobalProductStatus.ARCHIVED,
                                    product.getId());
                    if (conflicts > 0 && existing == null) {
                        skipped++;
                        lines.add(new PromoteLineResult(
                                itemId, null, "skipped", "Barcode already used by another global product", false));
                        continue;
                    }
                }

                product = globalProductRepository.saveAndFlush(product);
                boolean imageRehosted = false;
                if (sourceImageUrl != null) {
                    imageRehosted = rehostImage(product, sourceImageUrl);
                    if (imageRehosted) {
                        product = globalProductRepository.saveAndFlush(product);
                        imageRehosts++;
                    }
                }

                if (existing == null) {
                    created++;
                    matchIndex.register(product);
                    lines.add(new PromoteLineResult(
                            itemId,
                            product.getId(),
                            "created",
                            imageRehosted ? "Created with image" : "Created",
                            imageRehosted));
                } else {
                    updated++;
                    matchIndex.register(product);
                    lines.add(new PromoteLineResult(
                            itemId,
                            product.getId(),
                            "updated",
                            imageRehosted ? "Updated with image" : "Updated",
                            imageRehosted));
                }
            } catch (Exception ex) {
                log.warn("Promote failed for item {}: {}", itemId, ex.toString());
                skipped++;
                lines.add(new PromoteLineResult(
                        itemId,
                        null,
                        "skipped",
                        ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName(),
                        false));
            }
        }

        return new PromoteResponse(created, updated, skipped, imageRehosts, lines);
    }

    private void applyItemFields(
            GlobalProduct product,
            Item item,
            String status,
            BigDecimal sellingPrice,
            ItemType itemType,
            Category category,
            String catalogId
    ) {
        product.setName(item.getName());
        product.setBrand(blankToNull(item.getBrand()));
        product.setSize(blankToNull(item.getSize()));
        product.setDescription(blankToNull(item.getDescription()));
        product.setBarcode(blankToNull(item.getBarcode()));
        product.setSkuTemplate(blankToNull(item.getSku()));
        product.setUnitType(blankToNull(item.getUnitType()) != null ? item.getUnitType() : "each");
        product.setWeighed(item.isWeighed());
        product.setSellable(item.isSellable());
        product.setStocked(item.isStocked());
        product.setRecommendedBuyingPrice(item.getBuyingPrice());
        product.setRecommendedSellingPrice(sellingPrice != null ? sellingPrice : null);
        product.setDefaultMinStockLevel(item.getMinStockLevel());
        product.setDefaultReorderLevel(item.getReorderLevel());
        product.setDefaultReorderQty(item.getReorderQty());
        product.setHasExpiry(item.isHasExpiry());
        product.setExpiresAfterDays(item.getExpiresAfterDays());
        product.setItemTypeKeyHint(itemType != null ? itemType.getTypeKey() : "goods");
        product.setStatus(status);
        if (category != null) {
            product.setGlobalCategoryId(ensureGlobalCategory(catalogId, category).getId());
        }
    }

    private GlobalCategory ensureGlobalCategory(String catalogId, Category tenantCategory) {
        return ensureGlobalCategory(catalogId, tenantCategory, new HashSet<>());
    }

    private GlobalCategory ensureGlobalCategory(
            String catalogId,
            Category tenantCategory,
            Set<String> visitingTenantIds
    ) {
        String slug = blankToNull(tenantCategory.getSlug());
        if (slug == null) {
            slug = slugify(tenantCategory.getName());
        }
        final String resolvedSlug = slug;
        Optional<GlobalCategory> existing =
                globalCategoryRepository.findByCatalogIdAndSlug(catalogId, resolvedSlug);
        if (existing.isPresent()) {
            GlobalCategory found = existing.get();
            // Backfill parent link when promote previously flattened the tree.
            String desiredParentId = resolveGlobalParentId(catalogId, tenantCategory, visitingTenantIds);
            if (desiredParentId != null
                    && (found.getParentId() == null || found.getParentId().isBlank())) {
                found.setParentId(desiredParentId);
                return globalCategoryRepository.save(found);
            }
            return found;
        }

        if (!visitingTenantIds.add(tenantCategory.getId())) {
            // Cycle guard — create without parent rather than recurse forever.
            return createGlobalCategory(catalogId, tenantCategory, resolvedSlug, null);
        }
        String parentGlobalId = resolveGlobalParentId(catalogId, tenantCategory, visitingTenantIds);
        return createGlobalCategory(catalogId, tenantCategory, resolvedSlug, parentGlobalId);
    }

    private String resolveGlobalParentId(
            String catalogId,
            Category tenantCategory,
            Set<String> visitingTenantIds
    ) {
        String parentTenantId = blankToNull(tenantCategory.getParentId());
        if (parentTenantId == null) {
            return null;
        }
        return categoryRepository
                .findByIdAndBusinessId(parentTenantId, tenantCategory.getBusinessId())
                .map(parent -> ensureGlobalCategory(catalogId, parent, visitingTenantIds).getId())
                .orElse(null);
    }

    private GlobalCategory createGlobalCategory(
            String catalogId,
            Category tenantCategory,
            String slug,
            String parentGlobalId
    ) {
        GlobalCategory created = new GlobalCategory();
        created.setCatalogId(catalogId);
        created.setName(tenantCategory.getName());
        created.setSlug(slug);
        created.setTenantCategorySlugHint(slug);
        created.setParentId(parentGlobalId);
        created.setPosition(tenantCategory.getPosition());
        created.setActive(true);
        return globalCategoryRepository.save(created);
    }

    private boolean rehostImage(GlobalProduct product, String sourceImageUrl) {
        if (!mediaStore.isConfigured()) {
            // Keep portable HTTPS URL even without re-host so tenants can still see covers
            if (blankToNull(product.getImageUrl()) == null) {
                product.setImageUrl(sourceImageUrl);
            }
            return false;
        }
        try {
            String folder = CloudinaryImageService.folderGlobalCatalog(product.getId());
            CloudinaryUploadResult uploaded = mediaStore.uploadFromRemoteUrl(sourceImageUrl, folder);
            if (uploaded == null
                    || blankToNull(uploaded.publicId()) == null
                    || blankToNull(uploaded.secureUrl()) == null) {
                return false;
            }
            String previous = blankToNull(product.getImagePublicId());
            product.setImageUrl(uploaded.secureUrl());
            product.setImagePublicId(uploaded.publicId());
            if (previous != null && !previous.equals(uploaded.publicId())) {
                try {
                    mediaStore.destroyImage(previous);
                } catch (Exception ignored) {
                    // orphan ok
                }
            }
            return true;
        } catch (Exception ex) {
            log.warn("Promote image re-host failed for {}: {}", product.getId(), ex.toString());
            if (blankToNull(product.getImageUrl()) == null) {
                product.setImageUrl(sourceImageUrl);
            }
            return false;
        }
    }

    private String resolveHttpsImageUrl(Item item) {
        List<ItemImage> images = itemImageRepository.findByItemIdOrderBySortOrderAscIdAsc(item.getId());
        for (ItemImage img : images) {
            String secure = blankToNull(img.getSecureUrl());
            if (secure != null && (secure.startsWith("http://") || secure.startsWith("https://"))) {
                return secure;
            }
        }
        String key = blankToNull(item.getImageKey());
        if (key != null && (key.startsWith("http://") || key.startsWith("https://"))) {
            return key;
        }
        return null;
    }

    private Map<String, BigDecimal> loadSellingPrices(String businessId, Set<String> itemIds) {
        Map<String, BigDecimal> out = new HashMap<>();
        if (itemIds.isEmpty()) {
            return out;
        }
        List<SellingPrice> rows = sellingPriceRepository.findOpenEndedBusinessWideForItemIds(businessId, itemIds);
        for (SellingPrice row : rows) {
            out.putIfAbsent(row.getItemId(), row.getPrice());
        }
        return out;
    }

    private Map<String, ItemType> loadItemTypes(String businessId) {
        Map<String, ItemType> out = new HashMap<>();
        for (ItemType type : itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(businessId)) {
            out.put(type.getId(), type);
        }
        return out;
    }

    private Map<String, Category> loadCategories(String businessId, Iterable<Item> items) {
        Map<String, Category> out = new HashMap<>();
        Set<String> ids = new HashSet<>();
        for (Item item : items) {
            if (blankToNull(item.getCategoryId()) != null) {
                ids.add(item.getCategoryId());
            }
        }
        // Walk parent chains so promote can rebuild the category tree.
        Set<String> frontier = new HashSet<>(ids);
        while (!frontier.isEmpty()) {
            Set<String> next = new HashSet<>();
            for (String id : frontier) {
                if (out.containsKey(id)) {
                    continue;
                }
                categoryRepository.findByIdAndBusinessId(id, businessId).ifPresent(c -> {
                    out.put(c.getId(), c);
                    if (blankToNull(c.getParentId()) != null) {
                        next.add(c.getParentId());
                    }
                });
            }
            frontier = next;
        }
        return out;
    }

    private void publishAudit(PromoteRequest request, PromoteResponse result, String actorId) {
        try {
            Map<String, Object> metadata = new LinkedHashMap<>();
            metadata.put("sourceBusinessId", request.sourceBusinessId());
            metadata.put("itemCount", request.itemIds().size());
            metadata.put("createdCount", result.createdCount());
            metadata.put("updatedCount", result.updatedCount());
            metadata.put("skippedCount", result.skippedCount());
            metadata.put("imageRehostCount", result.imageRehostCount());
            metadata.put("publish", Boolean.TRUE.equals(request.publish()));
            if (request.catalogId() != null && !request.catalogId().isBlank()) {
                metadata.put("catalogId", request.catalogId().trim());
            }
            auditEventPublisher.publishSynchronous(auditEventBuilder
                    .builder(AuditEventCategory.PRODUCTS, AuditEventTypes.GLOBAL_PRODUCT_PROMOTED, AuditEventSeverity.INFO)
                    .businessId("platform")
                    .actor(actorId, AuditEventActorType.USER)
                    .target("global_catalog", requireCatalog(request.catalogId()).getId())
                    .source("super_admin_portal")
                    .metadata(metadata)
                    .build());
        } catch (Exception ex) {
            log.warn("Failed to audit promote: {}", ex.toString());
        }
    }

    private static String currentSuperAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof String id) || id.isBlank()) {
            return null;
        }
        return id;
    }

    private Business requireBusiness(String businessId) {
        return businessRepository.findById(businessId)
                .filter(b -> b.getDeletedAt() == null)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source business not found"));
    }

    private GlobalCatalog requireCatalog(String catalogId) {
        if (catalogId == null || catalogId.isBlank()) {
            return globalCatalogRepository.findByCode(DEFAULT_CATALOG_CODE)
                    .or(() -> globalCatalogRepository.findFirstByStatusOrderByVersionDesc(GlobalProductStatus.PUBLISHED))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No global catalog available"));
        }
        String key = catalogId.trim();
        return globalCatalogRepository.findById(key)
                .or(() -> globalCatalogRepository.findByCode(key))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Global catalog not found"));
    }

    private static boolean isPreferred(Business business) {
        if (PREFERRED_SOURCE_BUSINESS_ID.equals(business.getId())) {
            return true;
        }
        String slug = business.getSlug() == null ? "" : business.getSlug().toLowerCase(Locale.ROOT);
        String name = business.getName() == null ? "" : business.getName().toLowerCase(Locale.ROOT);
        return PREFERRED_SOURCE_SLUG.equals(slug) || name.contains("palmart");
    }

    private static String normalizeOnConflict(String raw) {
        if (raw == null || raw.isBlank()) {
            return "update";
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if (!"update".equals(value) && !"skip".equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "onConflict must be update or skip");
        }
        return value;
    }

    private static String slugify(String name) {
        String slug = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
        slug = slug.replaceAll("^-+|-+$", "");
        return slug.isBlank() ? "category" : slug.substring(0, Math.min(slug.length(), 191));
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    /**
     * In-memory match index for promote upsert keys (barcode → source id → sku → name keys).
     */
    static final class GlobalMatchIndex {
        private final Map<String, GlobalProduct> byId = new HashMap<>();
        private final Map<String, GlobalProduct> byBarcode = new HashMap<>();
        private final Map<String, GlobalProduct> bySku = new HashMap<>();
        private final Map<String, GlobalProduct> byNameKey = new HashMap<>();

        static GlobalMatchIndex build(List<GlobalProduct> products) {
            GlobalMatchIndex index = new GlobalMatchIndex();
            for (GlobalProduct product : products) {
                index.register(product);
            }
            return index;
        }

        void register(GlobalProduct product) {
            byId.put(product.getId(), product);
            String barcode = blankToNull(product.getBarcode());
            if (barcode != null) {
                byBarcode.putIfAbsent(barcode, product);
            }
            String sku = blankToNull(product.getSkuTemplate());
            if (sku != null) {
                bySku.putIfAbsent(sku.toLowerCase(Locale.ROOT), product);
            }
            for (String key : CatalogProductMatchNormalizer.matchKeys(
                    product.getName(), product.getBrand(), product.getSize(), null)) {
                byNameKey.putIfAbsent(key, product);
            }
        }

        GlobalProduct findMatch(Item item) {
            String bySource = item.getGlobalProductSourceId();
            if (bySource != null && byId.containsKey(bySource)) {
                return byId.get(bySource);
            }
            if (byId.containsKey(item.getId())) {
                return byId.get(item.getId());
            }
            String barcode = blankToNull(item.getBarcode());
            if (barcode != null && byBarcode.containsKey(barcode)) {
                return byBarcode.get(barcode);
            }
            String sku = blankToNull(item.getSku());
            if (sku != null && bySku.containsKey(sku.toLowerCase(Locale.ROOT))) {
                return bySku.get(sku.toLowerCase(Locale.ROOT));
            }
            for (String key : CatalogProductMatchNormalizer.matchKeys(
                    item.getName(), item.getBrand(), item.getSize(), item.getVariantName())) {
                GlobalProduct hit = byNameKey.get(key);
                if (hit != null) {
                    return hit;
                }
            }
            return null;
        }

        String findMatchId(Item item) {
            GlobalProduct match = findMatch(item);
            return match == null ? null : match.getId();
        }
    }
}
