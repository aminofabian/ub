package zelisline.ub.tenancy.api.dto;

import java.util.Collection;
import java.util.Locale;
import java.util.Set;

/**
 * Canonical onboarding / profile shop-format identifiers.
 */
public final class StoreTypeCodes {

    public static final String PATTERN =
            "butchery|mini-mart|full-grocery|fresh-market|mixed-shop|cosmetics|wines-spirits|pharmacy|other"
                    + "|barbershop|salon|spa|nail-studio|beauty-parlour";

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

    private StoreTypeCodes() {}
}
