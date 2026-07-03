package zelisline.ub.catalog.application;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;

/** POS / sale-time checks and human-readable messages when an item cannot be sold. */
public final class ItemSellability {

    private ItemSellability() {
    }

    public static void requireSellable(Item item) {
        String violation = sellabilityViolation(item);
        if (violation != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, violation);
        }
    }

    /**
     * @return a sale-blocking message, or {@code null} when the item may be sold
     */
    public static String sellabilityViolation(Item item) {
        String label = itemLabel(item);
        if (!item.isActive()) {
            return label + " is inactive and cannot be sold";
        }
        if (!item.isSellable()) {
            if (item.getVariantOfItemId() == null) {
                return label + " is not sellable — if this is a product group, choose a specific variant";
            }
            return label + " is not marked as sellable";
        }
        return null;
    }

    public static String itemLabel(Item item) {
        String name = item.getName() == null || item.getName().isBlank()
                ? "Item"
                : item.getName().trim();
        String sku = item.getSku();
        if (sku != null && !sku.isBlank()) {
            return "\"" + name + "\" (SKU " + sku.trim() + ")";
        }
        return "\"" + name + "\"";
    }

    public static String linePrefixed(int lineNumber, String message) {
        return "Line " + lineNumber + ": " + message;
    }
}
