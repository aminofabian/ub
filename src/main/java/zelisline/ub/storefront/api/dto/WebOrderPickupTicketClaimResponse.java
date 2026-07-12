package zelisline.ub.storefront.api.dto;

/**
 * Result of claiming a one-time cashier pickup-ticket auto-print.
 *
 * @param claimed true only when this caller won the claim and may print
 * @param reason  {@code claimed}, {@code already_printed}, {@code too_old}, or {@code not_found}
 */
public record WebOrderPickupTicketClaimResponse(
        boolean claimed,
        String reason
) {
}
