package zelisline.ub.marketplace.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.ClaimSupplierUsernameRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalProfileRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalVisibilityRequest;
import zelisline.ub.marketplace.api.dto.ShopSupplierPageResponse;
import zelisline.ub.marketplace.application.ShopSupplierPageService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** A supplier shop's own marketplace page, for its owner or admin, signed in to the shop as usual. */
@RestController
@RequestMapping("/api/v1/supplier-page")
@RequiredArgsConstructor
public class ShopSupplierPageController {

    private static final String MANAGE_SETTINGS = "hasPermission(null, 'business.manage_settings')";

    private final ShopSupplierPageService service;
    private final zelisline.ub.marketplace.application.ShopSupplierTeamService teamService;
    private final zelisline.ub.marketplace.application.SupplierShopPortalSessionService portalSessionService;

    @GetMapping
    @PreAuthorize(MANAGE_SETTINGS)
    public ShopSupplierPageResponse get(HttpServletRequest request) {
        return service.get(TenantRequestIds.resolveBusinessId(request));
    }

    @PatchMapping
    @PreAuthorize(MANAGE_SETTINGS)
    public ShopSupplierPageResponse updateProfile(
            @Valid @RequestBody PatchSupplierPortalProfileRequest body, HttpServletRequest request) {
        return service.updateProfile(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/visibility")
    @PreAuthorize(MANAGE_SETTINGS)
    public ShopSupplierPageResponse updateVisibility(
            @RequestBody PatchSupplierPortalVisibilityRequest body, HttpServletRequest request) {
        return service.updateVisibility(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PostMapping("/username")
    @PreAuthorize(MANAGE_SETTINGS)
    public ShopSupplierPageResponse claimUsername(
            @Valid @RequestBody ClaimSupplierUsernameRequest body, HttpServletRequest request) {
        return service.claimUsername(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PostMapping("/list")
    @PreAuthorize(MANAGE_SETTINGS)
    public ShopSupplierPageResponse list(HttpServletRequest request) {
        return service.list(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/payout")
    @PreAuthorize(MANAGE_SETTINGS)
    public zelisline.ub.marketplace.api.dto.SupplierPortalPaymentDetailsResponse payout(HttpServletRequest request) {
        return service.payout(TenantRequestIds.resolveBusinessId(request));
    }

    @PatchMapping("/payout")
    @PreAuthorize(MANAGE_SETTINGS)
    public zelisline.ub.marketplace.api.dto.SupplierPortalPaymentDetailsResponse updatePayout(
            @Valid @RequestBody zelisline.ub.marketplace.api.dto.PatchSupplierPortalPaymentDetailsRequest body,
            HttpServletRequest request
    ) {
        zelisline.ub.platform.security.TenantPrincipal principal =
                zelisline.ub.platform.security.CurrentTenantUser.requireHuman(request);
        return service.updatePayout(TenantRequestIds.resolveBusinessId(request), principal.userId(), body);
    }

    /** Invites the supplier's portal team into this shop, each as a normal staff invitation. */
    @PostMapping("/team/invite-portal-team")
    @PreAuthorize(MANAGE_SETTINGS)
    public zelisline.ub.marketplace.api.dto.ShopTeamInviteResponse invitePortalTeam(HttpServletRequest request) {
        return teamService.invitePortalTeam(request, TenantRequestIds.resolveBusinessId(request));
    }

    /**
     * Opens the supplier portal for the signed-in shop user, already signed in: returns a portal session the
     * browser keeps for the portal's pages. For anyone who works the trade desk.
     */
    @PostMapping("/portal-session")
    @PreAuthorize("hasPermission(null, 'storefront.orders.read')")
    public zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse portalSession(HttpServletRequest request) {
        zelisline.ub.platform.security.TenantPrincipal principal =
                zelisline.ub.platform.security.CurrentTenantUser.requireHuman(request);
        return portalSessionService.open(request, TenantRequestIds.resolveBusinessId(request), principal.userId());
    }
}
