package zelisline.ub.storefront.api.dto;

/**
 * WhatsApp checkout capability resolved for a public storefront (scope §6).
 * Only present in checkout-options when the feature is actually offered:
 * a missing payload means "not offered".
 */
public record WhatsAppCheckoutOption(
        boolean enabled,
        /** Normalised digits for {@code https://wa.me/{digits}} (null when disabled). */
        String digits,
        /** "fallback" | "always" — the merchant's effective mode. */
        String mode,
        /** Optional merchant label (unused in V1). */
        String label,
        /** Optional greeting prepended to the message (≤ 120 chars). */
        String greeting,
        /** How long an unconfirmed order holds stock, in minutes. */
        Integer expiryMins
) {
}
