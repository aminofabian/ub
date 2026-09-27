package zelisline.ub.platform.adoption;

/**
 * Published once, when a purchased domain finishes provisioning and the shop
 * can be opened on that address.
 */
public record DomainLiveEvent(String businessId, String fqdn, String payerPhone) {
}
