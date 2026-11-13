package zelisline.ub.tenancy.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Where a shop's free platform address stands.
 *
 * @param available           false when this server issues no free addresses (no parent zone set)
 * @param suffix              the shared parent zone, e.g. {@code kiosk.ke}
 * @param currentHost         the shop's primary address today (may be a custom domain)
 * @param currentName         the part before the suffix, or null when the primary is a custom domain
 * @param otherAddresses      earlier free addresses that still open the shop
 * @param nextNewNameAllowedAt when a brand-new name may next be taken; null when there is no wait
 */
public record ShopAddressStatusResponse(
        boolean available,
        String suffix,
        String currentHost,
        String currentName,
        List<String> otherAddresses,
        Instant nextNewNameAllowedAt,
        int minLength,
        int maxLength
) {
}
