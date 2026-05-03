package zelisline.ub.storefront.application;

import java.math.BigDecimal;
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
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemImageRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.domain.SellingPrice;
import zelisline.ub.pricing.repository.SellingPriceRepository;
import zelisline.ub.storefront.api.dto.PublicCatalogItemCardResponse;
import zelisline.ub.storefront.api.dto.PublicCatalogItemDetailResponse;
import zelisline.ub.storefront.api.dto.PublicCatalogListResponse;
import zelisline.ub.storefront.api.dto.PublicCatalogVariantResponse;
import zelisline.ub.storefront.api.dto.PublicCategoryListResponse;
import zelisline.ub.storefront.api.dto.PublicCategoryResponse;
import zelisline.ub.storefront.api.dto.PublicItemImageResponse;
import zelisline.ub.storefront.api.dto.PublicStorefrontResponse;
import zelisline.ub.tenancy.api.dto.StorefrontSettingsResponse;

@Service
public class PublicStorefrontCatalogService {

    private static final int DEFAULT_PAGE_SIZE = 24;
    private static final int MAX_PAGE_SIZE = 100;

    private final PublicStorefrontContextService storefrontContextService;
    private final ItemRepository itemRepository;
    private final SellingPriceRepository sellingPriceRepository;
    private final ItemImageRepository itemImageRepository;
    private final CategoryRepository categoryRepository;

    public PublicStorefrontCatalogService(
            PublicStorefrontContextService storefrontContextService,
            ItemRepository itemRepository,
            SellingPriceRepository sellingPriceRepository,
            ItemImageRepository itemImageRepository,
            CategoryRepository categoryRepository
    ) {
        this.storefrontContextService = storefrontContextService;
        this.itemRepository = itemRepository;
        this.sellingPriceRepository = sellingPriceRepository;
        this.itemImageRepository = itemImageRepository;
        this.categoryRepository = categoryRepository;
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
                loadFeaturedCards(ctx)
        );
    }

    @Transactional(readOnly = true)
    public PublicCatalogListResponse listItems(String slug, String q, String categoryId, String cursor, int limit) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        int sz = clampLimit(limit);
        String qq = blankToNull(q);
        String cat = blankToNull(categoryId);
        boolean catUnset = cat == null;
        Collection<String> categoryIds = List.of("");
        if (!catUnset) {
            categoryIds = resolveCategorySubtreeIds(ctx.business().getId(), cat);
            if (categoryIds.isEmpty()) {
                return new PublicCatalogListResponse(ctx.business().getCurrency(), List.of(), null);
            }
        }
        Pageable pg = PageRequest.of(0, sz, Sort.by(Sort.Direction.ASC, "id"));
        Slice<Item> slice = itemRepository.searchStorefrontCatalog(
                ctx.business().getId(), qq, catUnset, categoryIds, blankToNull(cursor), pg);
        List<Item> items = slice.getContent();
        String next = slice.hasNext() && !items.isEmpty() ? items.getLast().getId() : null;
        return new PublicCatalogListResponse(ctx.business().getCurrency(), toCards(ctx, items), next);
    }

    @Transactional(readOnly = true)
    public PublicCatalogItemDetailResponse getItemDetail(String slug, String itemId) {
        PublicStorefrontContext ctx = storefrontContextService.requireForSlug(slug);
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, ctx.business().getId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found"));
        if (!item.isWebPublished() || !item.isActive()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Not found");
        }
        String parentId = item.getVariantOfItemId() != null ? item.getVariantOfItemId() : item.getId();
        return new PublicCatalogItemDetailResponse(
                item.getId(),
                item.getName(),
                blankToNull(item.getDescription()),
                blankToNull(item.getVariantName()),
                item.getVariantOfItemId(),
                ctx.business().getCurrency(),
                singlePrice(ctx, item.getId()),
                listImagesForItem(itemId),
                listPublishedVariants(ctx, parentId)
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
        List<PublicCategoryResponse> rows = sorted.stream()
                .map(c -> new PublicCategoryResponse(
                        c.getId(),
                        c.getName(),
                        blankToNull(c.getParentId()),
                        c.getSlug()))
                .toList();
        return new PublicCategoryListResponse(rows);
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
        return toCards(ctx, ordered);
    }

    private List<PublicCatalogItemCardResponse> toCards(PublicStorefrontContext ctx, List<Item> items) {
        if (items.isEmpty()) {
            return List.of();
        }
        List<String> itemIds = items.stream().map(Item::getId).toList();
        Map<String, BigDecimal> prices = loadPrices(ctx.business().getId(), ctx.catalogBranch().getId(), itemIds);
        Map<String, String> thumbs = firstGalleryUrlByItemIds(itemIds);
        return items.stream()
                .map(i -> new PublicCatalogItemCardResponse(
                        i.getId(),
                        i.getName(),
                        blankToNull(i.getVariantName()),
                        thumbs.get(i.getId()),
                        prices.get(i.getId())
                ))
                .toList();
    }

    private Map<String, BigDecimal> loadPrices(String businessId, String branchId, List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return Map.of();
        }
        List<SellingPrice> rows = sellingPriceRepository.findOpenEndedForBranchAndItemIds(businessId, branchId, itemIds);
        Map<String, BigDecimal> out = new HashMap<>();
        for (SellingPrice sp : rows) {
            out.putIfAbsent(sp.getItemId(), sp.getPrice());
        }
        return out;
    }

    private BigDecimal singlePrice(PublicStorefrontContext ctx, String itemId) {
        List<SellingPrice> rows = sellingPriceRepository.findOpenEndedForBranchAndItemIds(
                ctx.business().getId(), ctx.catalogBranch().getId(), List.of(itemId));
        return rows.isEmpty() ? null : rows.getFirst().getPrice();
    }

    private List<PublicItemImageResponse> listImagesForItem(String itemId) {
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

    private List<PublicCatalogVariantResponse> listPublishedVariants(PublicStorefrontContext ctx, String parentItemId) {
        List<Item> rows = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                ctx.business().getId(), parentItemId);
        List<Item> published = rows.stream().filter(i -> i.isWebPublished() && i.isActive()).toList();
        if (published.isEmpty()) {
            return List.of();
        }
        List<String> ids = published.stream().map(Item::getId).toList();
        Map<String, BigDecimal> prices = loadPrices(ctx.business().getId(), ctx.catalogBranch().getId(), ids);
        Map<String, String> thumbs = firstGalleryUrlByItemIds(ids);
        return published.stream()
                .map(v -> new PublicCatalogVariantResponse(
                        v.getId(),
                        v.getName(),
                        blankToNull(v.getVariantName()),
                        thumbs.get(v.getId()),
                        prices.get(v.getId())
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
}
