package zelisline.ub.storefront.api.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Merchant-initiated customer message for a web order (scope P0 #5/#6).
 *
 * <p>{@code kind = "payment_request"} sends the standard "complete payment" nudge;
 * {@code kind = "custom"} sends the merchant's own {@code text}. WhatsApp first,
 * SMS fallback — see {@code WebOrderCustomerMessageService}.
 */
public record SendWebOrderMessageRequest(
        @NotBlank String kind,
        String text
) {
}
