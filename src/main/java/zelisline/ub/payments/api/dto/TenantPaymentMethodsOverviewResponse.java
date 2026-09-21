package zelisline.ub.payments.api.dto;

import java.time.Instant;
import java.util.List;

/**
 * Super-admin overview of every tenant payment method — counts plus destination details
 * (till / paybill / bank account) without exposing API credentials.
 */
public record TenantPaymentMethodsOverviewResponse(
        int totalBusinesses,
        int activeBusinesses,
        int tenantsWithAnyMethod,
        int tenantsWithCustody,
        int tenantsWithByo,
        int tenantsWithManual,
        int tenantsWithoutMethods,
        int activeConfigs,
        int inactiveConfigs,
        String custodyProvider,
        List<TenantPaymentMethodRow> methods
) {
    public record TenantPaymentMethodRow(
            String configId,
            String businessId,
            String businessName,
            String businessSlug,
            boolean businessActive,
            String gatewayType,
            String label,
            String status,
            boolean isDefault,
            /** till | paybill | null for credentialed BYO */
            String destinationType,
            String tillNumber,
            String businessNumber,
            String accountNumber,
            /** Human-readable landing spot, e.g. "Till 556677" or "Equity Bank · Acc …" */
            String destinationSummary,
            String custodyProvider,
            Instant updatedAt
    ) {
    }
}
