package zelisline.ub.tenancy.api.dto;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical onboarding / profile shop-format identifiers.
 */
public final class StoreTypeCodes {

    public static final String PATTERN =
            "butchery|mini-mart|full-grocery|fresh-market|mixed-shop|cosmetics|wines-spirits|pharmacy|other"
                    + "|barbershop|salon|spa|nail-studio|beauty-parlour"
                    + "|restaurant|cafe|fast-food|bar"
                    + "|lodging"
                    + "|clinic";

    /** Every valid code, derived from {@link #PATTERN} so the two never drift. */
    public static final List<String> ALL = List.of(PATTERN.split("\\|"));

    /**
     * A wholesale supplier's shop (ADR 0038). Assigned by the platform when a supplier opens its
     * shop from the supplier portal; it is not in {@link #PATTERN} or {@link #ALL}, so onboarding
     * and the formats picker never offer it and a request cannot ask for it. As with every other
     * format, branch on {@link #isSupplierBusiness}, never on the code by name.
     */
    public static final Set<String> SUPPLIER_TYPES = Set.of("supplier");

    /** Formats only the platform sets or clears. A shop keeps them through any change it makes itself. */
    public static final Set<String> SYSTEM_TYPES = SUPPLIER_TYPES;

    private static final Set<String> ALL_SET = java.util.stream.Stream
            .concat(ALL.stream(), SYSTEM_TYPES.stream())
            .collect(java.util.stream.Collectors.toUnmodifiableSet());

    /** True when one of the business's formats is a supplier's wholesale shop. */
    public static boolean isSupplierBusiness(Collection<String> storeTypes) {
        if (storeTypes == null) {
            return false;
        }
        for (String raw : storeTypes) {
            if (raw != null && SUPPLIER_TYPES.contains(raw.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** True when {@code code} is a known store-format code (case-insensitive, trimmed). */
    public static boolean isValidCode(String code) {
        if (code == null) {
            return false;
        }
        return ALL_SET.contains(code.trim().toLowerCase(Locale.ROOT));
    }

    /**
     * Shop formats that take orders and prepare food or drink (ADR 0020). As with service formats,
     * a type only picks defaults and wording; no backend behaviour may branch on an individual one.
     */
    public static final Set<String> FOOD_TYPES = Set.of("restaurant", "cafe", "fast-food", "bar");

    /** True when any of the business's shop formats is a food format. */
    public static boolean isFoodBusiness(Collection<String> storeTypes) {
        if (storeTypes == null) {
            return false;
        }
        for (String raw : storeTypes) {
            if (raw != null && FOOD_TYPES.contains(raw.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Shop formats that sell services (ADR 0014). A store type only picks defaults and wording;
     * no backend behaviour may branch on an individual type — use {@link #isServiceBusiness}.
     */
    public static final Set<String> SERVICE_TYPES =
            Set.of("barbershop", "salon", "spa", "nail-studio", "beauty-parlour");

    /** True when any of the business's shop formats is a service format. */
    public static boolean isServiceBusiness(Collection<String> storeTypes) {
        if (storeTypes == null) {
            return false;
        }
        for (String raw : storeTypes) {
            if (raw != null && SERVICE_TYPES.contains(raw.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** Food formats where customers sit and pay after eating; a café or fast-food counter takes payment first. */
    public static final Set<String> TABLE_SERVICE_TYPES = Set.of("restaurant", "bar");

    /**
     * Shop formats that let rooms for the night (ADR 0028): a guest house, or a bar with rooms above
     * it. An add-on capability, not an exclusive format — a tenant is usually a bar <em>and</em>
     * lodging. A store type only picks defaults and wording; branch on {@link #isLodgingBusiness}.
     */
    public static final Set<String> LODGING_TYPES = Set.of("lodging");

    /** True when any of the business's shop formats lets rooms. */
    public static boolean isLodgingBusiness(Collection<String> storeTypes) {
        if (storeTypes == null) {
            return false;
        }
        for (String raw : storeTypes) {
            if (raw != null && LODGING_TYPES.contains(raw.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** True when the business has shop formats and every one of them is a food format. */
    public static boolean isFoodOnlyBusiness(Collection<String> storeTypes) {
        if (storeTypes == null || storeTypes.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (String raw : storeTypes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (!FOOD_TYPES.contains(raw.trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    /** True for a food-only business with at least one format that seats people at tables. */
    public static boolean isTableServiceBusiness(Collection<String> storeTypes) {
        if (!isFoodOnlyBusiness(storeTypes)) {
            return false;
        }
        for (String raw : storeTypes) {
            if (raw != null && TABLE_SERVICE_TYPES.contains(raw.trim().toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    /** True when the business has shop formats and every one of them is a service format: it sells no goods. */
    public static boolean isServiceOnlyBusiness(Collection<String> storeTypes) {
        if (storeTypes == null || storeTypes.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (String raw : storeTypes) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            if (!SERVICE_TYPES.contains(raw.trim().toLowerCase(Locale.ROOT))) {
                return false;
            }
            any = true;
        }
        return any;
    }

    private StoreTypeCodes() {}
}
