package zelisline.ub.storefront.api.dto;

import java.util.List;

import zelisline.ub.payments.domain.spi.DisplayInstructions;

/**
 * Payment options exposed on public storefront checkout.
 */
public record PublicCheckoutPaymentOptions(
        List<DisplayInstructions> manual,
        List<PublicOnlinePaymentMethod> online
) {
}
