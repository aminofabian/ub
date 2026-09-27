package zelisline.ub.platform.adoption;

/**
 * A merchant asked Kiosk to do domain or shop setup for them.
 */
public record DomainHelpRequestedEvent(
        String businessId,
        String kindLabel,
        String phone,
        String domain,
        String note,
        /** Flat fee in cents, or null when the request is a call with no set price. */
        Long feeCents
) {
}
