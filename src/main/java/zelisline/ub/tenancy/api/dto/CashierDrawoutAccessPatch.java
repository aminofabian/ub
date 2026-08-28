package zelisline.ub.tenancy.api.dto;

import java.util.List;

/**
 * Partial update for {@code settings.cashierDrawout}. Null fields are left unchanged.
 */
public record CashierDrawoutAccessPatch(
        /** {@code all} or {@code selected}. */
        String scope,
        List<String> userIds
) {
}
