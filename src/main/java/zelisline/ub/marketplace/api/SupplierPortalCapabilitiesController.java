package zelisline.ub.marketplace.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalCapabilitiesResponse;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;
import zelisline.ub.platform.security.CurrentSupplierUser;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/capabilities")
@RequiredArgsConstructor
public class SupplierPortalCapabilitiesController {

    private final PlatformSupplierPortalSettingsService portalSettingsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.catalog.read')")
    public SupplierPortalCapabilitiesResponse get() {
        CurrentSupplierUser.require();
        PlatformSupplierPortalSettings s = portalSettingsService.loadSingleton();
        return new SupplierPortalCapabilitiesResponse(
                s.isPortalEnabled(),
                s.isAllowProfileEdits(),
                s.isAllowPaymentDetailEdits(),
                s.isAllowProductEdits(),
                s.isRequireStoreApprovalProductEdits(),
                s.isAllowInvoiceDownloads(),
                s.isAllowStatementDownloads());
    }
}
