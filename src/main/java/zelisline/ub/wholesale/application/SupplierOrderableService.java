package zelisline.ub.wholesale.application;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;
import zelisline.ub.pricing.application.PricingService;
import zelisline.ub.wholesale.api.dto.SupplierOverviewResponse.OrderableProduct;
import zelisline.ub.wholesale.api.dto.SupplierOverviewResponse.OrderableSummary;

/**
 * "Can a buyer order this?" for a supplier's products. A product can be ordered when it has a selling price, is
 * published online and, if its stock is tracked, has stock. A photo is a hint, never a gate: a product with no
 * photo still counts. A pack takes its stock from the product it is a pack of.
 */
@Service
@RequiredArgsConstructor
public class SupplierOrderableService {

    static final String MISSING_PRICE = "price";
    static final String MISSING_ONLINE = "online";
    static final String MISSING_STOCK = "stock";
    static final int CLOSE_LIMIT = 50;

    private final ItemRepository itemRepository;
    private final PricingService pricingService;
    private final zelisline.ub.pharmacy.application.DispensingGate dispensingGate;
    private final zelisline.ub.catalog.application.PackageVariantStockResolver stockResolver;

    @Transactional(readOnly = true)
    public OrderableSummary summary(String businessId) {
        List<Item> items = sellable(businessId);
        Map<String, Item> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.getId(), i));
        Map<String, BigDecimal> prices = items.isEmpty()
                ? Map.of()
                : pricingService.getCurrentOpenSellingPricesForItems(businessId, null, byId.keySet());

        java.util.Set<String> counterOnly = counterOnly(businessId);
        int orderable = 0;
        int withoutPhoto = 0;
        int packsWithoutSize = 0;
        int soldAtCounterOnly = 0;
        List<OrderableProduct> close = new ArrayList<>();
        for (Item item : items) {
            if (counterOnly.contains(item.getId())) {
                // A medicine the shop may not sell online. It is not a product to fix, so it is not a failure.
                soldAtCounterOnly++;
                continue;
            }
            List<String> missing = missing(item, byId, prices);
            if (missing.isEmpty()) {
                orderable++;
                if (item.getImageKey() == null || item.getImageKey().isBlank()) {
                    withoutPhoto++;
                }
                // A hint, like the photo. A weighed product has no pack size and needs none.
                // The catalogue's own rule for how many base units one pack holds (PackageVariantStockResolver).
                if (item.isPackageVariant() && !item.isWeighed() && stockResolver.unitsPerSale(item) == null) {
                    packsWithoutSize++;
                }
            } else {
                close.add(new OrderableProduct(item.getId(), item.getName(), missing));
            }
        }
        // The products nearest to being orderable come first: they are the quickest to fix.
        close.sort(Comparator.comparingInt((OrderableProduct p) -> p.missing().size())
                .thenComparing(OrderableProduct::name, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)));
        int notOrderable = close.size();
        return new OrderableSummary(
                items.size() - soldAtCounterOnly, orderable, notOrderable, withoutPhoto, packsWithoutSize,
                soldAtCounterOnly,
                close.size() > CLOSE_LIMIT ? List.copyOf(close.subList(0, CLOSE_LIMIT)) : List.copyOf(close));
    }

    /** True when at least one product can be ordered. Only published products are priced, to keep this cheap. */
    @Transactional(readOnly = true)
    public boolean anyOrderable(String businessId) {
        List<Item> items = sellable(businessId);
        Map<String, Item> byId = new HashMap<>();
        items.forEach(i -> byId.put(i.getId(), i));
        List<Item> published = items.stream().filter(Item::isWebPublished).toList();
        if (published.isEmpty()) {
            return false;
        }
        Map<String, BigDecimal> prices = pricingService.getCurrentOpenSellingPricesForItems(
                businessId, null, published.stream().map(Item::getId).toList());
        java.util.Set<String> counterOnly = counterOnly(businessId);
        for (Item item : published) {
            if (!counterOnly.contains(item.getId()) && missing(item, byId, prices).isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /** Whether a buyer can order this one product now. */
    @Transactional(readOnly = true)
    public boolean isOrderable(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId).orElse(null);
        if (item == null || !item.isActive() || !item.isSellable() || counterOnly(businessId).contains(itemId)) {
            return false;
        }
        Map<String, Item> byId = new HashMap<>();
        byId.put(item.getId(), item);
        String holderId = stockResolver.stockHolderItemId(item);
        if (holderId != null && !holderId.equals(item.getId())) {
            itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(holderId, businessId)
                    .ifPresent(holder -> byId.put(holder.getId(), holder));
        }
        Map<String, BigDecimal> prices = pricingService.getCurrentOpenSellingPricesForItems(
                businessId, null, List.of(item.getId()));
        return missing(item, byId, prices).isEmpty();
    }

    /** Medicines that may only be sold at the counter. Empty for a shop that is not a chemist. */
    private java.util.Set<String> counterOnly(String businessId) {
        try {
            return new java.util.HashSet<>(dispensingGate.counterOnlyItemIds(businessId));
        } catch (RuntimeException e) {
            return java.util.Set.of();
        }
    }

    private List<Item> sellable(String businessId) {
        return itemRepository.findByBusinessIdAndDeletedAtIsNull(businessId).stream()
                .filter(i -> i.isActive() && i.isSellable())
                .toList();
    }

    private List<String> missing(Item item, Map<String, Item> byId, Map<String, BigDecimal> prices) {
        List<String> missing = new ArrayList<>();
        BigDecimal price = prices.get(item.getId());
        if (price == null || price.signum() <= 0) {
            missing.add(MISSING_PRICE);
        }
        if (!item.isWebPublished()) {
            missing.add(MISSING_ONLINE);
        }
        // Stock is held where the catalogue says it is: a pack or bundle sells from its parent's stock.
        String holderId = stockResolver.stockHolderItemId(item);
        Item stockHolder = holderId == null ? item : byId.getOrDefault(holderId, item);
        if (stockHolder.isStocked()
                && (stockHolder.getCurrentStock() == null || stockHolder.getCurrentStock().signum() <= 0)) {
            missing.add(MISSING_STOCK);
        }
        return missing;
    }
}
