package zelisline.ub.payments.application;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import zelisline.ub.payments.domain.spi.PaymentGateway;

/**
 * Registry of all {@link PaymentGateway} implementations, keyed by
 * {@link PaymentGateway#gatewayType()}.
 *
 * <p>Spring auto-discovers every {@code PaymentGateway} bean and populates
 * this registry. Callers resolve a gateway by type string:
 *
 * <pre>{@code
 * PaymentGateway gw = registry.get("KOPOKOPO");
 * }</pre>
 */
@Component
public class PaymentGatewayRegistry {

    private final Map<String, PaymentGateway> gateways;

    public PaymentGatewayRegistry(List<PaymentGateway> gatewayList) {
        this.gateways = gatewayList.stream()
                .collect(Collectors.toUnmodifiableMap(
                        PaymentGateway::gatewayType,
                        Function.identity()));
    }

    /**
     * Resolve a gateway by type string (e.g. {@code "KOPOKOPO"}).
     *
     * @throws IllegalArgumentException if no implementation is registered for the type
     */
    public PaymentGateway get(String gatewayType) {
        PaymentGateway gw = gateways.get(gatewayType);
        if (gw == null) {
            throw new IllegalArgumentException(
                    "No PaymentGateway implementation registered for type: " + gatewayType);
        }
        return gw;
    }

    /**
     * Returns {@code true} if an implementation is registered for the given type.
     */
    public boolean has(String gatewayType) {
        return gateways.containsKey(gatewayType);
    }
}
