package zelisline.ub.discounts.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Category;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.CategoryRepository;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.discounts.api.dto.ResolvedDiscountRef;
import zelisline.ub.discounts.api.dto.ResolvedPriceResponse;
import zelisline.ub.discounts.domain.Discount;
import zelisline.ub.discounts.domain.DiscountCategory;
import zelisline.ub.discounts.domain.DiscountExclusion;
import zelisline.ub.discounts.domain.DiscountItem;
import zelisline.ub.discounts.domain.DiscountMethods;
import zelisline.ub.discounts.domain.DiscountScopes;
import zelisline.ub.discounts.domain.DiscountSupplier;
import zelisline.ub.discounts.repository.DiscountCategoryRepository;
import zelisline.ub.discounts.repository.DiscountExclusionRepository;
import zelisline.ub.discounts.repository.DiscountItemRepository;
import zelisline.ub.discounts.repository.DiscountRepository;
import zelisline.ub.discounts.repository.DiscountSupplierRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.pricing.application.SuggestedSellPriceRounding;
import zelisline.ub.suppliers.domain.SupplierProduct;
import zelisline.ub.suppliers.repository.SupplierProductRepository;

@Service
@RequiredArgsConstructor
public class DiscountResolutionService {

    private static final int MONEY_SCALE = 2;
    private static final BigDecimal TOLERANCE = new BigDecimal("0.01");

    private final DiscountRepository discountRepository;
    private final DiscountItemRepository discountItemRepository;
    private final DiscountCategoryRepository discountCategoryRepository;
    private final DiscountSupplierRepository discountSupplierRepository;
    private final DiscountExclusionRepository discountExclusionRepository;
    private final CategoryRepository categoryRepository;
    private final SupplierProductRepository supplierProductRepository;
    private final ItemRepository itemRepository;
    private final PricingService pricingService;

    @Transactional(readOnly = true)
    public ResolvedPriceResponse resolveForItem(String businessId, String itemId, String branchId) {
        BigDecimal regular = pricingService.getCurrentOpenSellingPrice(businessId, itemId, branchId);
        if (regular == null || regular.signum() <= 0) {
            return noDiscount(regular);
        }
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).orElse(null);
        if (item == null || !isEligibleItem(item)) {
            return noDiscount(regular);
        }
        ResolutionContext ctx = loadContext(businessId, branchId, Instant.now());
        Discount winner = pickWinner(item, regular, ctx);
        return toResponse(regular, winner);
    }

    @Transactional(readOnly = true)
    public Map<String, ResolvedPriceResponse> resolveForItems(
            String businessId,
            String branchId,
            Collection<String> itemIds
    ) {
        if (itemIds == null || itemIds.isEmpty()) {
            return Map.of();
        }
        List<String> ids = itemIds.stream().filter(id -> id != null && !id.isBlank()).distinct().toList();
        if (ids.isEmpty()) {
            return Map.of();
        }
        Map<String, BigDecimal> regularPrices =
                pricingService.getCurrentOpenSellingPricesForItems(businessId, branchId, ids);
        List<Item> items = itemRepository.findByIdInAndBusinessIdAndDeletedAtIsNull(ids, businessId);
        Map<String, Item> itemsById = items.stream().collect(Collectors.toMap(Item::getId, i -> i, (a, b) -> a));
        ResolutionContext ctx = loadContext(businessId, branchId, Instant.now());
        Map<String, ResolvedPriceResponse> out = new HashMap<>();
        for (String itemId : ids) {
            BigDecimal regular = regularPrices.get(itemId);
            Item item = itemsById.get(itemId);
            if (regular == null || regular.signum() <= 0 || item == null || !isEligibleItem(item)) {
                out.put(itemId, noDiscount(regular));
                continue;
            }
            Discount winner = pickWinner(item, regular, ctx);
            out.put(itemId, toResponse(regular, winner));
        }
        return out;
    }

    public static boolean pricesMatch(BigDecimal a, BigDecimal b) {
        if (a == null || b == null) {
            return false;
        }
        return a.subtract(b).abs().compareTo(TOLERANCE) <= 0;
    }

    private static ResolvedPriceResponse noDiscount(BigDecimal regular) {
        if (regular == null) {
            return new ResolvedPriceResponse(null, null, BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY), null);
        }
        return new ResolvedPriceResponse(
                regular,
                regular,
                BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY),
                null);
    }

    private ResolvedPriceResponse toResponse(BigDecimal regular, Discount winner) {
        if (winner == null) {
            return noDiscount(regular);
        }
        BigDecimal finalPrice = applyMethod(regular, winner);
        BigDecimal saved = regular.subtract(finalPrice).max(BigDecimal.ZERO).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return new ResolvedPriceResponse(
                regular,
                finalPrice,
                saved,
                new ResolvedDiscountRef(
                        winner.getId(),
                        winner.getName(),
                        winner.getMethod(),
                        winner.getValue(),
                        winner.getScope()));
    }

    private BigDecimal applyMethod(BigDecimal regular, Discount discount) {
        BigDecimal raw;
        if (DiscountMethods.PERCENTAGE.equals(discount.getMethod())) {
            BigDecimal factor = BigDecimal.ONE.subtract(
                    discount.getValue().movePointLeft(2));
            raw = regular.multiply(factor);
        } else if (DiscountMethods.FIXED_AMOUNT.equals(discount.getMethod())) {
            raw = regular.subtract(discount.getValue());
        } else {
            return regular;
        }
        BigDecimal rounded = SuggestedSellPriceRounding.round(raw);
        if (rounded == null || rounded.signum() <= 0) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.UNNECESSARY);
        }
        return rounded;
    }

    private Discount pickWinner(Item item, BigDecimal regularPrice, ResolutionContext ctx) {
        List<Discount> matches = new ArrayList<>();
        for (Discount discount : ctx.discounts()) {
            if (matchesItem(item, regularPrice, discount, ctx)) {
                matches.add(discount);
            }
        }
        if (matches.isEmpty()) {
            return null;
        }
        matches.sort(Comparator
                .comparingInt(Discount::getPriority).reversed()
                .thenComparing(Discount::getPublishedAt, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(Discount::getCreatedAt));
        return matches.getFirst();
    }

    private boolean matchesItem(Item item, BigDecimal regularPrice, Discount discount, ResolutionContext ctx) {
        if (ctx.excludedItems(discount.getId()).contains(item.getId())) {
            return false;
        }
        if (DiscountMethods.FIXED_AMOUNT.equals(discount.getMethod())
                && discount.getValue().compareTo(regularPrice) > 0) {
            return false;
        }
        return switch (discount.getScope()) {
            case DiscountScopes.ITEM -> ctx.targetItems(discount.getId()).contains(item.getId());
            case DiscountScopes.CATEGORY -> matchesCategory(item, discount, ctx);
            case DiscountScopes.SUPPLIER -> matchesSupplier(item, discount, ctx);
            case DiscountScopes.STORE -> matchesStore(item, discount);
            default -> false;
        };
    }

    private boolean matchesCategory(Item item, Discount discount, ResolutionContext ctx) {
        String categoryId = blankToNull(item.getCategoryId());
        if (categoryId == null) {
            return false;
        }
        for (String targetCategoryId : ctx.targetCategories(discount.getId())) {
            if (ctx.categoryTreeIds(targetCategoryId).contains(categoryId)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesSupplier(Item item, Discount discount, ResolutionContext ctx) {
        for (DiscountSupplier row : ctx.supplierTargets(discount.getId())) {
            String supplierId = row.getId().getSupplierId();
            Set<String> itemIds = row.isIncludeAnyLinked()
                    ? ctx.anyLinkedItems(supplierId)
                    : ctx.primaryLinkedItems(supplierId);
            if (itemIds.contains(item.getId())) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesStore(Item item, Discount discount) {
        if (DiscountMethods.FIXED_AMOUNT.equals(discount.getMethod()) && item.isWeighed()) {
            return false;
        }
        return true;
    }

    private static boolean isEligibleItem(Item item) {
        return item.isActive() && item.isSellable() && item.getDeletedAt() == null;
    }

    private ResolutionContext loadContext(String businessId, String branchId, Instant now) {
        String brId = blankToNull(branchId);
        List<Discount> discounts = discountRepository.findResolvable(businessId, brId, now);
        if (discounts.isEmpty()) {
            return ResolutionContext.empty();
        }
        List<String> discountIds = discounts.stream().map(Discount::getId).toList();

        Map<String, Set<String>> targetItems = new HashMap<>();
        for (DiscountItem row : discountItemRepository.findByDiscountIds(discountIds)) {
            targetItems.computeIfAbsent(row.getId().getDiscountId(), k -> new HashSet<>())
                    .add(row.getId().getItemId());
        }

        Map<String, Set<String>> targetCategories = new HashMap<>();
        for (DiscountCategory row : discountCategoryRepository.findByDiscountIds(discountIds)) {
            targetCategories.computeIfAbsent(row.getId().getDiscountId(), k -> new HashSet<>())
                    .add(row.getId().getCategoryId());
        }

        Map<String, List<DiscountSupplier>> supplierTargets = new HashMap<>();
        for (DiscountSupplier row : discountSupplierRepository.findByDiscountIds(discountIds)) {
            supplierTargets.computeIfAbsent(row.getId().getDiscountId(), k -> new ArrayList<>()).add(row);
        }

        Map<String, Set<String>> excludedItems = new HashMap<>();
        for (DiscountExclusion row : discountExclusionRepository.findByDiscountIds(discountIds)) {
            excludedItems.computeIfAbsent(row.getId().getDiscountId(), k -> new HashSet<>())
                    .add(row.getId().getItemId());
        }

        Map<String, Category> categoriesById = new HashMap<>();
        for (Category c : categoryRepository.findByBusinessIdOrderByPositionAsc(businessId)) {
            categoriesById.put(c.getId(), c);
        }
        Map<String, Set<String>> categoryTreeIds = new HashMap<>();
        for (String catId : categoriesById.keySet()) {
            categoryTreeIds.put(catId, descendantsIncludingSelf(catId, categoriesById));
        }

        Map<String, Set<String>> primaryLinkedItems = new HashMap<>();
        Map<String, Set<String>> anyLinkedItems = new HashMap<>();
        Set<String> supplierIds = supplierTargets.values().stream()
                .flatMap(List::stream)
                .map(ds -> ds.getId().getSupplierId())
                .collect(Collectors.toSet());
        for (String supplierId : supplierIds) {
            List<SupplierProduct> links = supplierProductRepository.listForSupplier(businessId, supplierId);
            Set<String> any = new HashSet<>();
            Set<String> primary = new HashSet<>();
            for (SupplierProduct link : links) {
                if (link.getDeletedAt() != null || !link.isActive()) {
                    continue;
                }
                any.add(link.getItemId());
                if (link.isPrimaryLink()) {
                    primary.add(link.getItemId());
                }
            }
            anyLinkedItems.put(supplierId, any);
            primaryLinkedItems.put(supplierId, primary);
        }

        return new ResolutionContext(
                discounts,
                targetItems,
                targetCategories,
                supplierTargets,
                excludedItems,
                categoryTreeIds,
                primaryLinkedItems,
                anyLinkedItems);
    }

    private static Set<String> descendantsIncludingSelf(String rootId, Map<String, Category> byId) {
        Set<String> out = new HashSet<>();
        collectDescendants(rootId, byId, out, 0);
        return out;
    }

    private static void collectDescendants(String id, Map<String, Category> byId, Set<String> out, int depth) {
        if (id == null || depth > 128) {
            return;
        }
        out.add(id);
        for (Category c : byId.values()) {
            if (id.equals(c.getParentId())) {
                collectDescendants(c.getId(), byId, out, depth + 1);
            }
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }

    private record ResolutionContext(
            List<Discount> discounts,
            Map<String, Set<String>> targetItems,
            Map<String, Set<String>> targetCategories,
            Map<String, List<DiscountSupplier>> supplierTargets,
            Map<String, Set<String>> excludedItems,
            Map<String, Set<String>> categoryTreeIds,
            Map<String, Set<String>> primaryLinkedItems,
            Map<String, Set<String>> anyLinkedItems
    ) {
        static ResolutionContext empty() {
            return new ResolutionContext(List.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
        }

        Set<String> targetItems(String discountId) {
            return targetItems.getOrDefault(discountId, Set.of());
        }

        Set<String> targetCategories(String discountId) {
            return targetCategories.getOrDefault(discountId, Set.of());
        }

        List<DiscountSupplier> supplierTargets(String discountId) {
            return supplierTargets.getOrDefault(discountId, List.of());
        }

        Set<String> excludedItems(String discountId) {
            return excludedItems.getOrDefault(discountId, Set.of());
        }

        Set<String> categoryTreeIds(String categoryId) {
            return categoryTreeIds.getOrDefault(categoryId, Set.of());
        }

        Set<String> primaryLinkedItems(String supplierId) {
            return primaryLinkedItems.getOrDefault(supplierId, Set.of());
        }

        Set<String> anyLinkedItems(String supplierId) {
            return anyLinkedItems.getOrDefault(supplierId, Set.of());
        }
    }
}
