package zelisline.ub.platform.adoption;

/**
 * Published after a tenant's custom-domain order is paid (or stubbed-paid) and
 * moves into registration, so platform ops can provision and follow up.
 *
 * @param paymentCollected {@code true} when M-Pesa actually settled. Stub orders
 *                         are {@code false} so texts do not claim money arrived.
 */
public record DomainPurchasedEvent(
        String businessId,
        String orderId,
        String fqdn,
        String payerPhone,
        Long priceCents,
        boolean paymentCollected
) {
}
