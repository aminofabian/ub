package zelisline.ub.tenancy.api.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

public record DomainOrderResponse(
        String id,
        String businessId,
        String businessName,
        String businessSlug,
        String fqdn,
        String status,
        String nsStatus,
        Long priceCents,
        String currency,
        String registerUrl,
        String hostafricaDomainId,
        boolean vercelZoneReady,
        String domainMappingId,
        List<String> intendedNameservers,
        Map<String, Object> dnsInstructions,
        String lastError,
        String merchantMessage,
        Instant paidAt,
        String paymentCheckoutId,
        String paymentTxnId,
        String payerPhone,
        String lastStkStatus,
        boolean paymentAvailable,
        Instant createdAt,
        Instant updatedAt
) {
}
