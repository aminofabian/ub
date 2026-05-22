package zelisline.ub.catalog.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import lombok.RequiredArgsConstructor;
import zelisline.ub.catalog.domain.Item;
import zelisline.ub.catalog.repository.ItemRepository;

/**
 * Parent-child inventory for package / selling-unit variants.
 * <p>
 * The base (parent) product is the single source of truth for on-hand quantity.
 * Variant SKUs (single, tray, crate, …) sell in different units but always deduct
 * {@code soldQty × packagingUnitQty} from the parent's batches and {@code current_stock}.
 */
@Service
@RequiredArgsConstructor
public class PackageVariantStockResolver {

    private static final int QTY_SCALE = 4;

    private final ItemRepository itemRepository;

    public record StockPickResolution(
            String stockItemId,
            BigDecimal stockQuantity,
            boolean packageSale
    ) {
    }

    public Item requireSellableItem(String businessId, String itemId) {
        Item item = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(itemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        if (!item.isSellable()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not sellable");
        }
        return item;
    }

    /**
     * Resolves catalog line quantity to physical stock movement on the holder item (parent or self).
     */
    public StockPickResolution resolvePick(String businessId, String soldItemId, BigDecimal soldQuantity) {
        Item sold = requireSellableItem(businessId, soldItemId);
        BigDecimal qty = soldQuantity == null ? BigDecimal.ZERO : soldQuantity.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (qty.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive");
        }
        BigDecimal unitsPerSale = unitsPerSale(sold);
        if (unitsPerSale != null) {
            String parentId = blankToNull(sold.getVariantOfItemId());
            if (parentId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "This sellable unit must be linked to a base product"
                );
            }
            Item parent = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(parentId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base product not found"));
            if (!parent.isStocked()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base product is not stocked");
            }
            BigDecimal stockQty = qty.multiply(unitsPerSale).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            return new StockPickResolution(parentId, stockQty, true);
        }
        if (!sold.isStocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not stocked");
        }
        return new StockPickResolution(soldItemId, qty, false);
    }

    /**
     * Converts inbound catalog quantity to holder stock (parent base units when applicable).
     * Does not require the catalog row to be sellable (e.g. opening balance on base product).
     */
    public StockPickResolution resolveInbound(String businessId, String catalogItemId, BigDecimal catalogQuantity) {
        Item catalog = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(catalogItemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        BigDecimal qty = catalogQuantity == null ? BigDecimal.ZERO : catalogQuantity.setScale(QTY_SCALE, RoundingMode.HALF_UP);
        if (qty.signum() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Quantity must be positive");
        }
        BigDecimal units = unitsPerSale(catalog);
        if (units != null) {
            String parentId = blankToNull(catalog.getVariantOfItemId());
            if (parentId == null) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "This unit must be linked to a base product"
                );
            }
            Item parent = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(parentId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base product not found"));
            if (!parent.isStocked()) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base product is not stocked");
            }
            BigDecimal stockQty = qty.multiply(units).setScale(QTY_SCALE, RoundingMode.HALF_UP);
            return new StockPickResolution(parentId, stockQty, true);
        }
        if (!catalog.isStocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item is not stocked");
        }
        return new StockPickResolution(catalog.getId(), qty, false);
    }

    /**
     * Base units deducted per one catalog unit sold (e.g. 30 eggs per tray, 1 egg per single).
     */
    public BigDecimal unitsPerSale(Item item) {
        if (item.isPackageVariant()) {
            return positiveUnits(item.getPackagingUnitQty());
        }
        String parentId = blankToNull(item.getVariantOfItemId());
        if (parentId == null) {
            return null;
        }
        BigDecimal packaged = positiveUnits(item.getPackagingUnitQty());
        if (packaged == null) {
            return null;
        }
        if (!item.isStocked()) {
            return packaged;
        }
        if (packaged.compareTo(BigDecimal.ONE) > 0) {
            return packaged;
        }
        // Single selling unit (1 egg) — same pool as parent when linked as a variant
        if (packaged.compareTo(BigDecimal.ONE) == 0) {
            return packaged;
        }
        return null;
    }

    public boolean sharesParentStock(Item item) {
        return unitsPerSale(item) != null && blankToNull(item.getVariantOfItemId()) != null;
    }

    /** Item row that owns {@code inventory_batches} and {@code current_stock} for this catalog SKU. */
    public String stockHolderItemId(Item item) {
        if (sharesParentStock(item)) {
            return blankToNull(item.getVariantOfItemId());
        }
        return item.getId();
    }

    /**
     * Loaded holder item for ledger / transfer / purchase inbound. Catalog SKU may be a package variant.
     */
    public Item requireInventoryHolder(String businessId, String catalogItemId) {
        Item catalog = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(catalogItemId, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Item not found"));
        String holderId = stockHolderItemId(catalog);
        Item holder = catalog;
        if (!holderId.equals(catalog.getId())) {
            holder = itemRepository.findByIdAndBusinessIdAndDeletedAtIsNull(holderId, businessId)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base product not found"));
        }
        if (!holder.isStocked()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Base product is not stocked");
        }
        return holder;
    }

    /**
     * For list/detail display when {@code branchId} stock is requested: package rows show
     * how many whole packages are available; base rows show raw units.
     */
    public BigDecimal displayStockQty(Item item, BigDecimal holderStock) {
        BigDecimal base = holderStock == null ? BigDecimal.ZERO : holderStock;
        BigDecimal units = unitsPerSale(item);
        if (units == null) {
            return base;
        }
        return base.divide(units, 0, RoundingMode.FLOOR);
    }

    public BigDecimal unitsPerPackage(Item item) {
        return unitsPerSale(item);
    }

    /**
     * All item ids whose active batches count toward one shared pool (parent + selling-unit variants).
     * Includes legacy rows that still hold stock on a child SKU (e.g. singles marked stocked).
     */
    public Set<String> branchStockPoolItemIds(String businessId, Item catalogItem) {
        String parentId = blankToNull(catalogItem.getVariantOfItemId());
        if (parentId == null) {
            parentId = catalogItem.getId();
        }
        Set<String> pool = new LinkedHashSet<>();
        pool.add(parentId);
        List<Item> siblings = itemRepository.findByBusinessIdAndVariantOfItemIdAndDeletedAtIsNullOrderBySkuAsc(
                businessId, parentId);
        for (Item sibling : siblings) {
            if (includeSiblingInStockPool(sibling)) {
                pool.add(sibling.getId());
            }
        }
        return pool;
    }

    /** Sum on-hand at a branch across the shared pool (batch totals keyed by item id). */
    public BigDecimal sumPoolStock(Item catalogItem, java.util.Map<String, BigDecimal> stockByItemId) {
        if (stockByItemId == null || stockByItemId.isEmpty()) {
            return BigDecimal.ZERO;
        }
        String businessId = catalogItem.getBusinessId();
        BigDecimal sum = BigDecimal.ZERO;
        for (String id : branchStockPoolItemIds(businessId, catalogItem)) {
            sum = sum.add(stockByItemId.getOrDefault(id, BigDecimal.ZERO));
        }
        return sum.setScale(QTY_SCALE, RoundingMode.HALF_UP);
    }

    private boolean includeSiblingInStockPool(Item sibling) {
        if (sibling.isPackageVariant() || sharesParentStock(sibling)) {
            return true;
        }
        String parentId = blankToNull(sibling.getVariantOfItemId());
        if (parentId == null) {
            return false;
        }
        return positiveUnits(sibling.getPackagingUnitQty()) != null;
    }

    private static BigDecimal positiveUnits(BigDecimal units) {
        if (units == null || units.signum() <= 0) {
            return null;
        }
        return units;
    }

    private static String blankToNull(String s) {
        if (s == null || s.isBlank()) {
            return null;
        }
        return s.trim();
    }
}
