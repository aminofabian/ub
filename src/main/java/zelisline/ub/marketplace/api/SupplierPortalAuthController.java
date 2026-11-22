package zelisline.ub.marketplace.api;

import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimCompleteRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimPublicConfigResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimSendCodeRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimSendCodeResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimVerifyCodeRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimVerifyCodeResponse;
import zelisline.ub.marketplace.api.dto.SupplierPortalClaimVerifyInviteRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalLoginResponse;
import zelisline.ub.marketplace.application.SupplierPortalAuthService;
import zelisline.ub.marketplace.application.SupplierPortalClaimService;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/auth")
@RequiredArgsConstructor
public class SupplierPortalAuthController {

    private final SupplierPortalAuthService supplierPortalAuthService;
    private final SupplierPortalClaimService supplierPortalClaimService;
    private final zelisline.ub.marketplace.application.SupplierShopSignInService supplierShopSignInService;

    @GetMapping("/claim/config")
    public SupplierPortalClaimPublicConfigResponse claimConfig() {
        return supplierPortalClaimService.publicConfig();
    }

    @PostMapping("/login")
    public SupplierPortalLoginResponse login(
            @Valid @RequestBody SupplierPortalLoginRequest request,
            jakarta.servlet.http.HttpServletRequest http
    ) {
        try {
            return supplierPortalAuthService.login(request, http);
        } catch (org.springframework.web.server.ResponseStatusException refused) {
            // A linked shop user may use their shop password here (P1-3). Tried only after the portal's own login
            // has refused, and any problem with it reports the original refusal.
            if (refused.getStatusCode().value() != 401) {
                throw refused;
            }
            java.util.Optional<SupplierPortalLoginResponse> viaShop;
            try {
                viaShop = supplierShopSignInService.signInToPortal(http, request.identifier(), request.password());
            } catch (RuntimeException ignored) {
                throw refused;
            }
            return viaShop.orElseThrow(() -> refused);
        }
    }

    @PostMapping("/claim/send-code")
    public SupplierPortalClaimSendCodeResponse sendClaimCode(
            @Valid @RequestBody SupplierPortalClaimSendCodeRequest request
    ) {
        return supplierPortalClaimService.sendCode(request.phone());
    }

    @PostMapping("/claim/verify-code")
    public SupplierPortalClaimVerifyCodeResponse verifyClaimCode(
            @Valid @RequestBody SupplierPortalClaimVerifyCodeRequest request
    ) {
        return supplierPortalClaimService.verifyCode(request.phone(), request.code());
    }

    @PostMapping("/claim/verify-invite")
    public SupplierPortalClaimVerifyCodeResponse verifyInvite(
            @Valid @RequestBody SupplierPortalClaimVerifyInviteRequest request
    ) {
        return supplierPortalClaimService.verifyInvite(request.code(), request.phone());
    }

    @PostMapping("/claim/complete")
    public SupplierPortalLoginResponse completeClaim(
            @Valid @RequestBody SupplierPortalClaimCompleteRequest request,
            jakarta.servlet.http.HttpServletRequest http
    ) {
        return supplierPortalClaimService.complete(request, http);
    }
}
