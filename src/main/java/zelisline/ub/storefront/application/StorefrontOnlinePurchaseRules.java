package zelisline.ub.storefront.application;

import java.math.BigDecimal;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.catalog.domain.Item;

/**
 * Rules for which catalog rows can be added to the public web cart, and which
 * quantities are valid (whole units vs fractional weight).
 */
public final class StorefrontOnlinePurchaseRules {

    public static final String WEB_CART = "web_cart";
    /**
     * Shown in the public catalogue but not sold online. Weighed items no longer use it; it now
     * marks medicines that must be dispensed at the counter (ADR 0033), which the catalogue
     * service decides per item because it needs a lookup this static class cannot do.
     */
    public static final String IN_STORE_ONLY = "in_store_only";

    /** Matches POS {@code SaleService} weighed quantity precision. */
    public static final int WEIGHTED_QTY_SCALE = 3;

    private StorefrontOnlinePurchaseRules() {
    }

    /**
     * Public catalog purchase mode. Weighed SKUs are sellable online
     * ({@link #WEB_CART}); fractional qty is gated separately via {@link #allowsFractionalQuantity}.
     */
    public static String resolveMode(Item item) {
        return WEB_CART;
    }

    public static boolean isWebCartEligible(Item item) {
        return item != null;
    }

    public static void requireWebCartEligible(Item item) {
        if (!isWebCartEligible(item)) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "This item is only available in store");
        }
    }

    public static boolean allowsFractionalQuantity(Item item) {
        return item != null && item.isWeighed();
    }

    /**
     * Non-weighed: whole units only. Weighed: up to {@link #WEIGHTED_QTY_SCALE} decimal places.
     */
    public static void requireValidQuantity(Item item, BigDecimal quantity) {
        if (quantity == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "quantity required");
        }
        if (allowsFractionalQuantity(item)) {
            BigDecimal stripped = quantity.stripTrailingZeros();
            if (stripped.scale() > WEIGHTED_QTY_SCALE) {
                throw new ResponseStatusException(
                        HttpStatus.BAD_REQUEST,
                        "Weight may have at most " + WEIGHTED_QTY_SCALE + " decimal places");
            }
            return;
        }
        requireWholeUnitQuantity(quantity);
    }

    /** Web checkout for piece/pack SKUs — no decimal kg. */
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
