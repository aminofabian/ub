package zelisline.ub.platform.adoption;

/**
 * Published after a tenant's Kiosk Pay account transitions to ACTIVE so platform
 * ops can follow up. Delivered via {@link PlatformAdoptionSmsNotifier}.
 */
public record KioskPayActivatedEvent(String businessId) {
}
