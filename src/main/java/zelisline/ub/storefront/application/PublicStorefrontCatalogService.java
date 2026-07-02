package zelisline.ub.storefront.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Slice;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.domain.ItemImage;
import zelisline.ub.catalog.domain.ItemType;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.catalog.repository.ItemTypeRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.purchasing.repository.InventoryBatchRepository;
import zelisline.ub.storefront.api.dto.PublicCatalogItemCardResponse;
import zelisline.ub.storefront.api.dto.PublicCatalogItemDetailResponse;
import zelisline.ub.storefront.api.dto.PublicCatalogListResponse;
import zelisline.ub.storefront.api.dto.PublicCatalogVariantResponse;
import zelisline.ub.storefront.api.dto.PublicCategoryListResponse;
import zelisline.ub.storefront.api.dto.PublicCategoryResponse;
import zelisline.ub.storefront.api.dto.PublicDepartmentListResponse;
import zelisline.ub.storefront.api.dto.PublicDepartmentResponse;
import zelisline.ub.storefront.api.dto.PublicItemImageResponse;
import zelisline.ub.storefront.api.dto.PublicStorefrontResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;

@Service
public class PublicStorefrontCatalogService {

    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE = 100;
    private static final int CATALOG_SCAN_PAGE = 64;

    private final PublicStorefrontContextService storefrontContextService;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;
    private final ItemImageRepository itemImageRepository;
    private final CategoryRepository categoryRepository;
    private final ItemTypeRepository itemTypeRepository;
    private final StorefrontCatalogStockService storefrontCatalogStockService;

    public PublicStorefrontCatalogService(
            PublicStorefrontContextService storefrontContextService,
            ItemRepository itemRepository,
            PricingService pricingService,
            ItemImageRepository itemImageRepository,
            CategoryRepository categoryRepository,
            ItemTypeRepository itemTypeRepository,
            StorefrontCatalogStockService storefrontCatalogStockService
    ) {
        this.storefrontContextService = storefrontContextService;
        this.itemRepository = itemRepository;
        this.pricingService = pricingService;
        this.itemImageRepository = itemImageRepository;
        this.categoryRepository = categoryRepository;
        this.itemTypeRepository = itemTypeRepository;
        this.storefrontCatalogStockService = storefrontCatalogStockService;
    }

    @Transactional(readOnly = true)
    public PublicStorefrontResponse getStorefront(String slug) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        StorefrontSettingsResponse sf = ctx.storefrontSettings();
        return new PublicStorefrontResponse(
                ctx.business().getName(),
                ctx.business().getSlug(),
                ctx.business().getCurrency(),
                ctx.catalogBranch().getId(),
                ctx.catalogBranch().getName(),
                sf.label(),
                sf.announcement(),
                loadFeaturedCards(ctx),
                listPublishedTypes(ctx)
        );
    }

    @Transactional(readOnly = true)
    public PublicCatalogListResponse listItems(
            String slug,
            String q,
            String categoryId,
            String typeId,
            String cursor,
            int limit
    ) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        int sz = clampLimit(limit);
        String qq = blankToNull(q);
        String cat = blankToNull(categoryId);
        boolean catUnset = cat == null;
        Collection<String> categoryIds = List.of("");
        if (!catUnset) {
            categoryIds = resolveCategorySubtreeIds(ctx.business().getId(), cat);
            if (categoryIds.isEmpty()) {
                return new PublicCatalogListResponse(ctx.business().getCurrency(), List.of(), null, 0L);
            }
        }
        String type = blankToNull(typeId);
        boolean typeUnset = type == null;
        if (!typeUnset && itemTypeRepository.findByIdAndBusinessId(type, ctx.business().getId()).isEmpty()) {
            return new PublicCatalogListResponse(ctx.business().getCurrency(), List.of(), null, 0L);
        }
        List<Item> items = fetchInStockCatalogPage(
                ctx, qq, catUnset, categoryIds, typeUnset, type, blankToNull(cursor), sz);
        String next = null;
        if (!items.isEmpty()
                && hasInStockCatalogAfter(ctx, qq, catUnset, categoryIds, typeUnset, type, items.getLast().getId())) {
            next = items.getLast().getId();
        }
        long total = countInStockCatalog(ctx, qq, catUnset, categoryIds, typeUnset, type, blankToNull(cursor));
        return new PublicCatalogListResponse(ctx.business().getCurrency(), toCards(ctx, items), next, total);
    }

    @Transactional(readOnly = true)
    public PublicCatalogItemDetailResponse getItemDetail(String slug, String idOrSku) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(idOrSku, ctx.business().getId())
                .orElseGet(() -> itemRepository.findByBusinessIdAndSkuAndDeletedAtIsNull(ctx.business().getId(), idOrSku)
                        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found")));
        if (!item.isWebPublished() || !item.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        BigDecimal onHand = storefrontCatalogStockService.displayQtyForItem(
                ctx.business().getId(), ctx.catalogBranch().getId(), item);
        if (onHand.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        String parentId = item.getVariantOfItemId() != null ? item.getVariantOfItemId() : item.getId();
        Item parent = loadParentItem(ctx, item);
        return new PublicCatalogItemDetailResponse(
                item.getId(),
                item.getSku(),
                item.getName(),
                blankToNull(item.getDescription()),
                blankToNull(item.getVariantName()),
                item.getVariantOfItemId(),
                ctx.business().getCurrency(),
                pricingService.getCurrentOpenSellingPrice(
                        ctx.business().getId(), item.getId(), ctx.catalogBranch().getId()),
                onHand,
                listImagesForItem(item, parent),
                listPublishedVariants(ctx, parentId),
                StorefrontOnlinePurchaseRules.resolveMode(item)
        );
    }

    @Transactional(readOnly = true)
    public PublicCatalogItemDetailResponse getItemByBarcode(String slug, String barcode) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        Item item = itemRepository.findByBusinessIdAndBarcodeAndDeletedAtIsNull(ctx.business().getId(), barcode)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!item.isWebPublished() || !item.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        BigDecimal onHand = storefrontCatalogStockService.displayQtyForItem(
                ctx.business().getId(), ctx.catalogBranch().getId(), item);
        if (onHand.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        String parentId = item.getVariantOfItemId() != null ? item.getVariantOfItemId() : item.getId();
        Item parent = loadParentItem(ctx, item);
        return new PublicCatalogItemDetailResponse(
                item.getId(),
                item.getSku(),
                item.getName(),
                blankToNull(item.getDescription()),
                blankToNull(item.getVariantName()),
                item.getVariantOfItemId(),
                ctx.business().getCurrency(),
                pricingService.getCurrentOpenSellingPrice(
                        ctx.business().getId(), item.getId(), ctx.catalogBranch().getId()),
                onHand,
                listImagesForItem(item, parent),
                listPublishedVariants(ctx, parentId),
                StorefrontOnlinePurchaseRules.resolveMode(item)
        );
    }

    @Transactional(readOnly = true)
    public PublicCategoryListResponse listPublishedCategories(String slug) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        List<String> assigned = itemRepository.findDistinctWebPublishedCategoryIds(ctx.business().getId());
        if (assigned.isEmpty()) {
            return new PublicCategoryListResponse(List.of());
        }
        List<Category> all = categoryRepository.findByBusinessIdOrderByPositionAsc(ctx.business().getId());
        Map<String, Category> byId = new LinkedHashMap<>();
        for (Category c : all) {
            byId.put(c.getId(), c);
        }
        LinkedHashSet<String> keep = new LinkedHashSet<>();
        for (String cid : assigned) {
            String cur = cid;
            while (cur != null && !cur.isBlank()) {
                if (!keep.add(cur)) {
                    break;
                }
                Category row = byId.get(cur);
                if (row == null) {
                    break;
                }
                cur = row.getParentId();
            }
        }
        Map<String, Integer> depthMemo = new HashMap<>();
        List<Category> filtered = all.stream()
                .filter(c -> keep.contains(c.getId()) && c.isActive())
                .toList();
        List<Category> sorted = filtered.stream()
                .sorted(Comparator
                        .comparing((Category c) -> categoryDepth(c, byId, depthMemo))
                        .thenComparingInt(Category::getPosition))
                .toList();

        Map<String, Long> directCounts = countStorefrontItemsByCategory(ctx);
        Map<String, List<String>> childrenByParent = new LinkedHashMap<>();
        for (Category c : all) {
            String pk = c.getParentId() == null ? "" : c.getParentId();
            childrenByParent.computeIfAbsent(pk, k -> new ArrayList<>()).add(c.getId());
        }
        Map<String, Long> subtreeCounts = new HashMap<>();
        for (Category c : sorted) {
            subtreeCounts.put(c.getId(), subtreeItemCount(c.getId(), directCounts, childrenByParent, subtreeCounts));
        }

        List<PublicCategoryResponse> rows = sorted.stream()
                .map(c -> new PublicCategoryResponse(
                        c.getId(),
                        c.getName(),
                        blankToNull(c.getParentId()),
                        c.getSlug(),
                        blankToNull(c.getIcon()),
                        subtreeCounts.getOrDefault(c.getId(), 0L)))
                .toList();
        return new PublicCategoryListResponse(rows);
    }

    @Transactional(readOnly = true)
    public PublicDepartmentListResponse listPublishedDepartments(String slug) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        return new PublicDepartmentListResponse(listPublishedTypes(ctx));
    }

    private List<PublicDepartmentResponse> listPublishedTypes(PublicStorefrontContext ctx) {
        List<String> assigned = itemRepository.findDistinctWebPublishedItemTypeIds(ctx.business().getId());
        if (assigned.isEmpty()) {
            return List.of();
        }
        Map<String, Long> counts = countStorefrontItemsByDepartment(ctx);
        List<ItemType> types = itemTypeRepository.findByBusinessIdOrderBySortOrderAsc(ctx.business().getId());
        return types.stream()
                .filter(t -> t.isActive() && assigned.contains(t.getId()))
                .map(t -> new PublicDepartmentResponse(
                        t.getId(),
                        t.getLabel(),
                        blankToNull(t.getIcon()),
                        counts.getOrDefault(t.getId(), 0L)))
                .filter(r -> r.itemCount() > 0)
                .toList();
    }

    private static int categoryDepth(Category c, Map<String, Category> byId, Map<String, Integer> memo) {
        Integer hit = memo.get(c.getId());
        if (hit != null) {
            return hit;
        }
        String p = c.getParentId();
        if (p == null || p.isBlank()) {
            memo.put(c.getId(), 0);
            return 0;
        }
        Category parent = byId.get(p);
        if (parent == null) {
            memo.put(c.getId(), 0);
            return 0;
        }
        int d = 1 + categoryDepth(parent, byId, memo);
        memo.put(c.getId(), d);
        return d;
    }

    private List<PublicCatalogItemCardResponse> loadFeaturedCards(PublicStorefrontContext ctx) {
        List<String> ids = ctx.storefrontSettings().featuredItemIds();
        if (ids.isEmpty()) {
            return List.of();
        }
        List<Item> ordered = new ArrayList<>();
        for (String id : ids) {
            itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(id, ctx.business().getId())
                    .filter(i -> i.isWebPublished() && i.isActive())
                    .ifPresent(ordered::add);
        }
        if (ordered.isEmpty()) {
            return List.of();
        }
        Map<String, BigDecimal> qtyMap = storefrontCatalogStockService.displayQtyForItems(
                ctx.business().getId(), ctx.catalogBranch().getId(), ordered);
        List<Item> inStock = ordered.stream()
                .filter(i -> qtyMap.getOrDefault(i.getId(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0)
                .toList();
        return toCards(ctx, inStock);
    }

    private List<PublicCatalogItemCardResponse> toCards(PublicStorefrontContext ctx, List<Item> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> itemIds = items.stream().map(Item::getId).toList();
        Map<String, BigDecimal> prices = loadPrices(ctx, itemIds);
        Map<String, BigDecimal> qty = storefrontCatalogStockService.displayQtyForItems(
                ctx.business().getId(), ctx.catalogBranch().getId(), items);
        Map<String, BigDecimal> buyingPrices = pricingService.getLatestBuyingPricesForItems(
                ctx.business().getId(), itemIds);
        Map<String, String> thumbs = resolveThumbnailUrlsForItems(items);
        return items.stream()
                .map(i -> {
                    BigDecimal latestBuying = buyingPrices.get(i.getId());
                    BigDecimal fallbackBuying = latestBuying != null ? latestBuying : i.getBuyingPrice();
                    return new PublicCatalogItemCardResponse(
                            i.getId(),
                            i.getSku(),
                            i.getName(),
                            blankToNull(i.getVariantName()),
                            thumbs.get(i.getId()),
                            prices.get(i.getId()),
                            qty.getOrDefault(i.getId(), BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP),
                            fallbackBuying,
                            StorefrontOnlinePurchaseRules.resolveMode(i)
                    );
                })
                .toList();
    }

    private Map<String, BigDecimal> loadPrices(PublicStorefrontContext ctx, List<String> itemIds) {
        return pricingService.getCurrentOpenSellingPricesForItems(
                ctx.business().getId(), ctx.catalogBranch().getId(), itemIds);
    }

    private Item loadParentItem(PublicStorefrontContext ctx, Item item) {
        String parentId = item.getVariantOfItemId();
        if (parentId == null || parentId.isBlank()) {
            return null;
        }
        return itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(parentId, ctx.business().getId())
                .orElse(null);
    }

    private List<PublicItemImageResponse> listImagesForItem(Item item, Item parentOrNull) {
        List<PublicItemImageResponse> own = loadGalleryImages(item.getId());
        if (!own.isEmpty()) {
            return own;
        }
        String fromKey = publicUrlFromImageKey(item.getImageKey());
        if (fromKey != null) {
            return List.of(new PublicItemImageResponse(fromKey, null, null, null));
        }
        if (parentOrNull == null) {
            return List.of();
        }
        List<PublicItemImageResponse> inherited = loadGalleryImages(parentOrNull.getId());
        if (!inherited.isEmpty()) {
            return inherited;
        }
        fromKey = publicUrlFromImageKey(parentOrNull.getImageKey());
        if (fromKey != null) {
            return List.of(new PublicItemImageResponse(fromKey, null, null, null));
        }
        return List.of();
    }

    private List<PublicItemImageResponse> loadGalleryImages(String itemId) {
        List<ItemImage> imgs = itemImageRepository.findByItemIdOrderBySortOrderAscIdAsc(itemId);
        List<PublicItemImageResponse> out = new ArrayList<>();
        for (ItemImage img : imgs) {
            String url = resolveImagePublicUrl(img);
            if (url == null) {
                continue;
            }
            out.add(new PublicItemImageResponse(url, blankToNull(img.getAltText()), img.getWidth(), img.getHeight()));
        }
        return out;
    }

    private Map<String, String> resolveThumbnailUrlsForItems(List<Item> items) {
        if (items.isEmpty()) {
            return Map.of();
        }
        List<String> galleryIds = new ArrayList<>();
        for (Item item : items) {
            galleryIds.add(item.getId());
            String parentId = item.getVariantOfItemId();
            if (parentId != null && !parentId.isBlank()) {
                galleryIds.add(parentId);
            }
        }
        Map<String, String> gallery = firstGalleryUrlByItemIds(galleryIds);
        Map<String, Item> parentsById = new LinkedHashMap<>();
        for (Item item : items) {
            String parentId = item.getVariantOfItemId();
            if (parentId == null || parentId.isBlank() || parentsById.containsKey(parentId)) {
                continue;
            }
            itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(parentId, item.getBusinessId())
                    .ifPresent(parent -> parentsById.put(parentId, parent));
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Item item : items) {
            String thumb = resolveItemThumbnail(item, gallery, parentsById.get(item.getVariantOfItemId()));
            if (thumb != null) {
                out.put(item.getId(), thumb);
            }
        }
        return out;
    }

    private static String resolveItemThumbnail(Item item, Map<String, String> galleryByItemId, Item parentOrNull) {
        String own = thumbnailFromItem(item, galleryByItemId);
        if (own != null) {
            return own;
        }
        if (parentOrNull == null) {
            return null;
        }
        return thumbnailFromItem(parentOrNull, galleryByItemId);
    }

    private static String thumbnailFromItem(Item item, Map<String, String> galleryByItemId) {
        String fromKey = publicUrlFromImageKey(item.getImageKey());
        if (fromKey != null) {
            return fromKey;
        }
        return galleryByItemId.get(item.getId());
    }

    private static String publicUrlFromImageKey(String imageKey) {
        if (imageKey == null || imageKey.isBlank()) {
            return null;
        }
        String k = imageKey.trim();
        if (k.startsWith("http://") || k.startsWith("https://")) {
            return k;
        }
        return null;
    }

    private List<PublicCatalogVariantResponse> listPublishedVariants(PublicStorefrontContext ctx, String parentItemId) {
        List<Item> rows = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                ctx.business().getId(), parentItemId);
        List<Item> published = rows.stream().filter(i -> i.isWebPublished() && i.isActive()).toList();
        if (published.isEmpty()) {
            return List.of();
        }
        Map<String, BigDecimal> qtyByItem = storefrontCatalogStockService.displayQtyForItems(
                ctx.business().getId(), ctx.catalogBranch().getId(), published);
        List<Item> publishedInStock = published.stream()
                .filter(v -> qtyByItem.getOrDefault(v.getId(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0)
                .toList();
        if (publishedInStock.isEmpty()) {
            return List.of();
        }
        List<String> inStockIds = publishedInStock.stream().map(Item::getId).toList();
        Map<String, BigDecimal> prices = loadPrices(ctx, inStockIds);
        Map<String, String> thumbs = resolveThumbnailUrlsForItems(publishedInStock);
        return publishedInStock.stream()
                .map(v -> new PublicCatalogVariantResponse(
                        v.getId(),
                        v.getSku(),
                        v.getName(),
                        blankToNull(v.getVariantName()),
                        thumbs.get(v.getId()),
                        prices.get(v.getId()),
                        qtyByItem.getOrDefault(v.getId(), BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP),
                        StorefrontOnlinePurchaseRules.resolveMode(v)
                ))
                .toList();
    }

    private Map<String, String> firstGalleryUrlByItemIds(List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        Sort galleryOrder = Sort.by(
                Sort.Order.asc("itemId"), Sort.Order.asc("sortOrder"), Sort.Order.asc("id"));
        List<ItemImage> rows = itemImageRepository.findByItemIdIn(itemIds, galleryOrder);
        Map<String, String> out = new LinkedHashMap<>();
        for (ItemImage img : rows) {
            String url = resolveImagePublicUrl(img);
            if (url == null) {
                continue;
            }
            out.putIfAbsent(img.getItemId(), url);
        }
        return out;
    }

    private static String resolveImagePublicUrl(ItemImage img) {
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

    private List<String> resolveCategorySubtreeIds(String businessId, String categoryId) {
        if (categoryRepository.findByIdAndBusinessId(categoryId, businessId).isEmpty()) {
            return List.of();
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

    private static int clampLimit(int limit) {
        if (limit <= 0) {
            return DEFAULT_PAGE_SIZE;
        }
        return Math.min(limit, MAX_PAGE_SIZE);
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }

    private Map<String, Long> countStorefrontItemsByCategory(PublicStorefrontContext ctx) {
        Map<String, Long> out = new HashMap<>();
        String scan = null;
        while (true) {
            Slice<Item> slice = itemRepository.searchStorefrontCatalog(
                    ctx.business().getId(),
                    null,
                    true,
                    List.of(""),
                    true,
                    "",
                    scan,
                    ctx.catalogBranch().getId(),
                    PageRequest.of(0, CATALOG_SCAN_PAGE, Sort.by(Sort.Direction.ASC, "id")));
            List<Item> batch = slice.getContent();
            if (batch.isEmpty()) {
                break;
            }
            Map<String, BigDecimal> qty = storefrontCatalogStockService.displayQtyForItems(
                    ctx.business().getId(), ctx.catalogBranch().getId(), batch);
            for (Item item : batch) {
                if (qty.getOrDefault(item.getId(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String catId = item.getCategoryId();
                if (catId != null && !catId.isBlank()) {
                    out.merge(catId, 1L, Long::sum);
                }
            }
            if (!slice.hasNext()) {
                break;
            }
            scan = batch.getLast().getId();
        }
        return out;
    }

    private Map<String, Long> countStorefrontItemsByDepartment(PublicStorefrontContext ctx) {
        Map<String, Long> out = new HashMap<>();
        String scan = null;
        while (true) {
            Slice<Item> slice = itemRepository.searchStorefrontCatalog(
                    ctx.business().getId(),
                    null,
                    true,
                    List.of(""),
                    true,
                    "",
                    scan,
                    ctx.catalogBranch().getId(),
                    PageRequest.of(0, CATALOG_SCAN_PAGE, Sort.by(Sort.Direction.ASC, "id")));
            List<Item> batch = slice.getContent();
            if (batch.isEmpty()) {
                break;
            }
            Map<String, BigDecimal> qty = storefrontCatalogStockService.displayQtyForItems(
                    ctx.business().getId(), ctx.catalogBranch().getId(), batch);
            for (Item item : batch) {
                if (qty.getOrDefault(item.getId(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) <= 0) {
                    continue;
                }
                String deptId = item.getItemTypeId();
                if (deptId != null && !deptId.isBlank()) {
                    out.merge(deptId, 1L, Long::sum);
                }
            }
            if (!slice.hasNext()) {
                break;
            }
            scan = batch.getLast().getId();
        }
        return out;
    }

    private List<Item> fetchInStockCatalogPage(
            PublicStorefrontContext ctx,
            String q,
            boolean catUnset,
            Collection<String> categoryIds,
            boolean deptUnset,
            String departmentId,
            String cursor,
            int limit
    ) {
        List<Item> page = new ArrayList<>();
        String scan = cursor;
        while (page.size() < limit) {
            Slice<Item> slice = itemRepository.searchStorefrontCatalog(
                    ctx.business().getId(),
                    q,
                    catUnset,
                    categoryIds,
                    deptUnset,
                    departmentId == null ? "" : departmentId,
                    scan,
                    ctx.catalogBranch().getId(),
                    PageRequest.of(0, CATALOG_SCAN_PAGE, Sort.by(Sort.Direction.ASC, "id")));
            List<Item> batch = slice.getContent();
            if (batch.isEmpty()) {
                break;
            }
            Map<String, BigDecimal> qty = storefrontCatalogStockService.displayQtyForItems(
                    ctx.business().getId(), ctx.catalogBranch().getId(), batch);
            for (Item item : batch) {
                if (page.size() >= limit) {
                    break;
                }
                if (qty.getOrDefault(item.getId(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0) {
                    page.add(item);
                }
            }
            if (!slice.hasNext()) {
                break;
            }
            scan = batch.getLast().getId();
        }
        return page;
    }

    private boolean hasInStockCatalogAfter(
            PublicStorefrontContext ctx,
            String q,
            boolean catUnset,
            Collection<String> categoryIds,
            boolean deptUnset,
            String departmentId,
            String afterItemId
    ) {
        String scan = afterItemId;
        while (true) {
            Slice<Item> slice = itemRepository.searchStorefrontCatalog(
                    ctx.business().getId(),
                    q,
                    catUnset,
                    categoryIds,
                    deptUnset,
                    departmentId == null ? "" : departmentId,
                    scan,
                    ctx.catalogBranch().getId(),
                    PageRequest.of(0, CATALOG_SCAN_PAGE, Sort.by(Sort.Direction.ASC, "id")));
            List<Item> batch = slice.getContent();
            if (batch.isEmpty()) {
                return false;
            }
            Map<String, BigDecimal> qty = storefrontCatalogStockService.displayQtyForItems(
                    ctx.business().getId(), ctx.catalogBranch().getId(), batch);
            for (Item item : batch) {
                if (!item.getId().equals(afterItemId)
                        && qty.getOrDefault(item.getId(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0) {
                    return true;
                }
            }
            if (!slice.hasNext()) {
                return false;
            }
            scan = batch.getLast().getId();
        }
    }

    private long countInStockCatalog(
            PublicStorefrontContext ctx,
            String q,
            boolean catUnset,
            Collection<String> categoryIds,
            boolean deptUnset,
            String departmentId,
            String cursor
    ) {
        long count = 0;
        String scan = cursor;
        while (true) {
            Slice<Item> slice = itemRepository.searchStorefrontCatalog(
                    ctx.business().getId(),
                    q,
                    catUnset,
                    categoryIds,
                    deptUnset,
                    departmentId == null ? "" : departmentId,
                    scan,
                    ctx.catalogBranch().getId(),
                    PageRequest.of(0, CATALOG_SCAN_PAGE, Sort.by(Sort.Direction.ASC, "id")));
            List<Item> batch = slice.getContent();
            if (batch.isEmpty()) {
                break;
            }
            Map<String, BigDecimal> qty = storefrontCatalogStockService.displayQtyForItems(
                    ctx.business().getId(), ctx.catalogBranch().getId(), batch);
            for (Item item : batch) {
                if (qty.getOrDefault(item.getId(), BigDecimal.ZERO).compareTo(BigDecimal.ZERO) > 0) {
                    count++;
                }
            }
            if (!slice.hasNext()) {
                break;
            }
            scan = batch.getLast().getId();
        }
        return count;
    }

    private static long subtreeItemCount(String categoryId,
                                         Map<String, Long> directCounts,
                                         Map<String, List<String>> childrenByParent,
                                         Map<String, Long> memo) {
        Long hit = memo.get(categoryId);
        if (hit != null) {
            return hit;
        }
        long total = directCounts.getOrDefault(categoryId, 0L);
        for (String child : childrenByParent.getOrDefault(categoryId, List.of())) {
            total += subtreeItemCount(child, directCounts, childrenByParent, memo);
        }
        memo.put(categoryId, total);
        return total;
    }
}
