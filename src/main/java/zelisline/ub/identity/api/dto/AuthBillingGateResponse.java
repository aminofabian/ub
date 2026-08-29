package zelisline.ub.identity.api.dto;

import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;

/**
 * Optional billing gate returned on login when the tenant is suspended for unpaid
 * subscription — drives the renewal wall (SUBSCRIPTION_BILLING_SCOPE.md §8).
 */
public record AuthBillingGateResponse(
        String subscriptionBillingStatus,
        String suspensionReason,
        SubscriptionBillingDtos.RenewalQuoteResponse renewalQuote
) {
}
