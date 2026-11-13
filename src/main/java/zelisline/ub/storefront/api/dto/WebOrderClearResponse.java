package zelisline.ub.storefront.api.dto;

/**
 * Result of clearing every open web order for a business (optionally one branch).
 * Completed pickups are left alone. {@code moreRemaining} is true when the
 * request stopped before the queue was empty.
 */
public record WebOrderClearResponse(int voided, int failed, boolean moreRemaining) {
}
