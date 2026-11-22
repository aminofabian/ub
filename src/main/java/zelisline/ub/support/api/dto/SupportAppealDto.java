package zelisline.ub.support.api.dto;

/** A supplier's appeal about one counted order, carried on an {@code ORDER_APPEAL} message. */
public record SupportAppealDto(
        String orderId
) {
}
