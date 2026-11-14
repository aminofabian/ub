package zelisline.ub.tenancy.api.dto;

import java.time.Instant;
import java.util.Map;

public record DomainResponse(
        String id,
        String businessId,
        String domain,
        boolean primary,
        /** When true, this host sends visitors to the shop's main address. */
        boolean redirectToPrimary,
        boolean active,
        String status,
        String source,
        String zoneSource,
        Instant verifiedAt,
        Map<String, Object> dnsInstructions,
        String lastError
) {
}
