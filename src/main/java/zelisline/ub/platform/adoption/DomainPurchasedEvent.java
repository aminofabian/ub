package zelisline.ub.platform.adoption;

/**
 * Published after a tenant's custom-domain order is paid (or stubbed-paid) and
 * moves into registration, so platform ops can provision and follow up.
 */
public record DomainPurchasedEvent(String businessId, String fqdn) {
}
