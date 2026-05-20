package zelisline.ub.payments.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.SupplierPayoutSettingsRequest;
import zelisline.ub.payments.api.dto.SupplierPayoutSettingsResponse;
import zelisline.ub.payments.application.SupplierPayoutSettingsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/payments/supplier-payout")
@RequiredArgsConstructor
public class SupplierPayoutSettingsController {

    private final SupplierPayoutSettingsService supplierPayoutSettingsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public SupplierPayoutSettingsResponse get(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return supplierPayoutSettingsService.getSettings(TenantRequestIds.resolveBusinessId(request));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public SupplierPayoutSettingsResponse update(
            @Valid @RequestBody SupplierPayoutSettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return supplierPayoutSettingsService.updateSettings(
                TenantRequestIds.resolveBusinessId(request),
                body);
    }
}
