package zelisline.ub.inventory.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import zelisline.ub.catalog.application.ItemCatalogService;
import zelisline.ub.catalog.application.ProductDisplayName;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;

/** Loads the product facts a counter needs: shelf name, category and photo. */
@Component
@RequiredArgsConstructor
public class CountItemCatalog {

    private final ItemRepository itemRepository;
    private final CategoryRepository categoryRepository;
    private final ItemCatalogService itemCatalogService;

    public record Context(
            Map<String, Item> itemsById,
            Map<String, Category> categoriesById,
            Map<String, String> thumbs
    ) {
        public String categoryName(Item item) {
            if (item == null || item.getCategoryId() == null) {
                return null;
            }
            Category category = categoriesById.get(item.getCategoryId());
            return category != null ? category.getName() : null;
        }
    }

    public Context load(String businessId, List<String> itemIds) {
        if (itemIds.isEmpty()) {
            return new Context(Map.of(), Map.of(), Map.of());
        }
        Map<String, Item> itemsById = itemRepository
                .findByIdInAndBusinessIdAndDeletedAtIsNull(itemIds, businessId)
                .stream()
                .collect(Collectors.toMap(Item::getId, i -> i));
        return new Context(
                itemsById,
                loadCategories(businessId, itemsById.values()),
                loadThumbs(businessId, itemIds, itemsById.values())
        );
    }

    /** Shelf label: same family + option folding as receipts / catalog. */
    public static String displayName(Item item, String fallbackId) {
        if (item == null) {
            return fallbackId != null ? fallbackId : "";
        }
        String composed = ProductDisplayName.forItem(item);
        if (!composed.isBlank()) {
            return composed;
        }
        String base = item.getName();
        return base != null && !base.isBlank() ? base.trim() : "Item";
    }

    private Map<String, Category> loadCategories(String businessId, Collection<Item> items) {
        Set<String> categoryIds = items.stream()
                .map(Item::getCategoryId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (categoryIds.isEmpty()) {
            return Map.of();
        }
        return categoryRepository.findByBusinessIdOrderByPositionAsc(businessId)
                .stream()
                .filter(c -> categoryIds.contains(c.getId()))
                .collect(Collectors.toMap(Category::getId, c -> c));
    }

    /** Variants without their own photo borrow the parent product image. */
    private Map<String, String> loadThumbs(
            String businessId,
            List<String> itemIds,
            Collection<Item> items
    ) {
        Map<String, String> thumbs = new LinkedHashMap<>(
                itemCatalogService.resolveThumbnailUrls(businessId, itemIds)
        );
        Set<String> parentIds = items.stream()
                .filter(i -> !thumbs.containsKey(i.getId()))
                .map(Item::getVariantOfItemId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        if (parentIds.isEmpty()) {
            return thumbs;
        }
        Map<String, String> parentThumbs =
                itemCatalogService.resolveThumbnailUrls(businessId, parentIds);
        for (Item item : items) {
            String parentId = item.getVariantOfItemId();
            if (thumbs.containsKey(item.getId()) || parentId == null) {
                continue;
            }
            String parentThumb = parentThumbs.get(parentId);
            if (parentThumb != null && !parentThumb.isBlank()) {
                thumbs.put(item.getId(), parentThumb);
            }
        }
        return thumbs;
    }
}
