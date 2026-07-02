package zelisline.ub.storefront.application;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;

/**
 * Rules for which catalog rows can be added to the public web cart (P5 butcher / shop).
 */
public final class StorefrontOnlinePurchaseRules {

    public static final String WEB_CART = "web_cart";
    public static final String IN_STORE_ONLY = "in_store_only";

    private StorefrontOnlinePurchaseRules() {
    }

    public static String resolveMode(Item item) {
        if (item != null && item.isWeighed()) {
            return IN_STORE_ONLY;
        }
        return WEB_CART;
    }

    public static boolean isWebCartEligible(Item item) {
        return WEB_CART.equals(resolveMode(item));
    }

    public static void requireWebCartEligible(Item item) {
        if (!isWebCartEligible(item)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This item is only available in store");
        }
    }

    /** Web checkout uses whole units (trays, packs, pieces) — no decimal kg. */
    public static void requireWholeUnitQuantity(BigDecimal quantity) {
        if (quantity == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity required");
        }
        if (quantity.stripTrailingZeros().scale() > 0) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Online orders use whole units only");
        }
    }
}
