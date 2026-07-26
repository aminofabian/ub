package zelisline.ub.marketplace.api;

import java.util.ArrayList;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalCapabilitiesResponse;
import zelisline.ub.marketplace.domain.SupplierUser;
import zelisline.ub.marketplace.domain.SupplierUserRoles;
import zelisline.ub.marketplace.repository.SupplierUserRepository;
import zelisline.ub.platform.application.PlatformSupplierPortalSettingsService;
import zelisline.ub.platform.domain.PlatformSupplierPortalSettings;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/capabilities")
@RequiredArgsConstructor
public class SupplierPortalCapabilitiesController {

    private final PlatformSupplierPortalSettingsService portalSettingsService;
    private final SupplierUserRepository supplierUserRepository;

    @GetMapping
    @PreAuthorize("hasRole('SUPPLIER')")
    public SupplierPortalCapabilitiesResponse get() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        PlatformSupplierPortalSettings s = portalSettingsService.loadSingleton();
        // Prefer DB role over JWT claim so role changes (or stale tokens) don't hide money nav.
        String roleKey = supplierUserRepository
                .findByIdAndMarketplaceSupplierId(principal.userId(), principal.marketplaceSupplierId())
                .map(SupplierUser::getRoleKey)
                .orElse(principal.roleKey());
        var permissions = new ArrayList<>(SupplierUserRoles.permissionsFor(roleKey));
        boolean canViewMoney = permissions.contains(SupplierUserRoles.PERM_MONEY_READ);
        boolean canManageTeam = permissions.contains(SupplierUserRoles.PERM_TEAM_MANAGE);
        return new SupplierPortalCapabilitiesResponse(
                s.isPortalEnabled(),
                s.isAllowProfileEdits() && permissions.contains(SupplierUserRoles.PERM_PROFILE_WRITE),
                s.isAllowPaymentDetailEdits() && canViewMoney,
                s.isAllowProductEdits() && permissions.contains(SupplierUserRoles.PERM_CATALOG_WRITE),
                s.isRequireStoreApprovalProductEdits(),
                s.isAllowInvoiceDownloads() && canViewMoney,
                s.isAllowStatementDownloads() && canViewMoney,
                SupplierUserRoles.normalize(roleKey),
                permissions,
                canViewMoney,
                canManageTeam);
    }
}
