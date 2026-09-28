package zelisline.ub.payments.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.GatewayConfigResponse;
import zelisline.ub.payments.api.dto.ReviewDarajaStorefrontRequest;
import zelisline.ub.payments.api.dto.TenantPaymentMethodsOverviewResponse;
import zelisline.ub.payments.application.PaymentGatewayConfigService;
import zelisline.ub.payments.application.SuperAdminTenantPaymentMethodsService;

/**
 * Cross-tenant payment method inventory for Super Admin ops —
 * who has till/paybill/bank/BYO configured, with destination details.
 */
@RestController
@RequestMapping("/api/v1/super-admin/payments/tenant-methods")
@RequiredArgsConstructor
@PreAuthorize("hasRole('SUPER_ADMIN')")
public class SuperAdminTenantPaymentMethodsController {

    private final SuperAdminTenantPaymentMethodsService service;
    private final PaymentGatewayConfigService configService;

    @GetMapping
    public TenantPaymentMethodsOverviewResponse overview() {
        return service.overview();
    }

    @PostMapping("/{configId}/storefront")
    public GatewayConfigResponse reviewStorefront(
            @PathVariable String configId,
            @Valid @RequestBody ReviewDarajaStorefrontRequest body
    ) {
        return configService.reviewStorefront(configId, body.decision());
    }
}
