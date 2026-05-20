package zelisline.ub.payments.infrastructure;

import java.util.Map;

import org.springframework.stereotype.Component;

import zelisline.ub.payments.domain.GatewayType;
import zelisline.ub.payments.domain.PaymentGatewayConfig;
import zelisline.ub.payments.domain.spi.DisplayInstructions;
import zelisline.ub.payments.domain.spi.PaymentGateway;
import zelisline.ub.payments.domain.spi.StkPushRequest;
import zelisline.ub.payments.domain.spi.StkPushResponse;
import zelisline.ub.payments.domain.spi.StkStatusResponse;
import zelisline.ub.payments.domain.spi.ValidationResult;
import zelisline.ub.payments.domain.spi.WebhookResult;

/**
 * Display-only payment gateway for manual methods (Till, Paybill, Bank).
 *
 * <p>This implementation never makes API calls. It provides:
 * <ul>
 *   <li>Display instructions from the config's {@code displayInstructionsJson}</li>
 *   <li>Validation that the display instructions JSON is well-formed</li>
 * </ul>
 *
 * <p>STK Push, status query, and webhook methods all throw
 * {@link UnsupportedOperationException} — manual methods are not
 * interactive gateways.
 */
@Component
public class ManualPaymentGateway implements PaymentGateway {

    @Override
    public String gatewayType() {
        return GatewayType.MANUAL.name();
    }

    @Override
    public StkPushResponse initiateStkPush(StkPushRequest request) {
        throw new UnsupportedOperationException("Manual payment methods do not support STK Push");
    }

    @Override
    public StkStatusResponse queryStkStatus(String gatewayCheckoutRequestId) {
        throw new UnsupportedOperationException("Manual payment methods do not support status queries");
    }

    @Override
    public WebhookResult processWebhook(Map<String, String> headers, String rawBody) {
        throw new UnsupportedOperationException("Manual payment methods do not receive webhooks");
    }

    @Override
    public DisplayInstructions getDisplayInstructions(String businessId) {
        // Manual instructions are per-config, not per-business.
        // The PaymentDisplayController aggregates them from all ACTIVE configs.
        return null;
    }

    @Override
    public ValidationResult validateConfiguration(PaymentGatewayConfig config) {
        // Manual gateways skip the test flow (DRAFT → ACTIVE directly).
        // The display instructions JSON is validated at save time by the controller.
        return ValidationResult.success();
    }
}
