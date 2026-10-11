package zelisline.ub.storefront.api.dto;

/**
 * Outcome of a merchant-initiated customer message. {@code sent} is false when
 * neither WhatsApp nor SMS could deliver; {@code detail} explains why so the
 * dashboard can toast it.
 */
public record WebOrderMessageResponse(
        boolean sent,
        String channel,
        String detail
) {
}
