package zelisline.ub.payments.api;

import java.util.List;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.PlatformGatewayRequest;
import zelisline.ub.payments.api.dto.PlatformGatewayResponse;
import zelisline.ub.payments.application.PlatformPaymentGatewayService;

/**
 * Super-admin controller for managing which payment gateways are
 * available on the platform.
 *
 * <p>Paths under {@code /api/v1/super-admin/} are secured by
 * {@code hasRole("SUPER_ADMIN")} in {@code SecurityConfig} and
 * bypass tenant resolution in {@code DomainBusinessResolverFilter}.
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/payments/platform-gateways")
@RequiredArgsConstructor
public class PlatformPaymentGatewayController {

    private final PlatformPaymentGatewayService platformPaymentGatewayService;

    @GetMapping
    public List<PlatformGatewayResponse> listAll() {
        return platformPaymentGatewayService.listAll();
    }

    @PatchMapping("/{gatewayType}")
    public PlatformGatewayResponse update(
            @PathVariable String gatewayType,
            @Valid @RequestBody PlatformGatewayRequest request
    ) {
        return platformPaymentGatewayService.update(gatewayType, request);
    }
}
