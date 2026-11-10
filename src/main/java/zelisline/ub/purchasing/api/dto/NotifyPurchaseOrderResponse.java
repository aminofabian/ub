package zelisline.ub.purchasing.api.dto;

import java.util.List;

/** One entry per recipient that was requested; recipients that were not requested are absent. */
public record NotifyPurchaseOrderResponse(List<Delivery> deliveries) {

    /**
     * @param recipient   {@code admin} or {@code supplier}
     * @param channel     the channel the caller asked for
     * @param outcome     {@code sent}, {@code failed} or {@code skipped}
     * @param detail      short, user-safe explanation (never contains credentials)
     * @param phoneMasked the number that was used, masked, or {@code null} when none was found
     */
    public record Delivery(
            String recipient,
            String channel,
            String outcome,
            String detail,
            String phoneMasked
    ) {
    }
}
