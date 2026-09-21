package zelisline.ub.payments.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.TenantPaymentMethodsOverviewResponse;
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

    @GetMapping
    public TenantPaymentMethodsOverviewResponse overview() {
        return service.overview();
    }
}
