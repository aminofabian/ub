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

    @GetMapping("/claim/config")
    public SupplierPortalClaimPublicConfigResponse claimConfig() {
        return supplierPortalClaimService.publicConfig();
    }

    @PostMapping("/login")
    public SupplierPortalLoginResponse login(
            @Valid @RequestBody SupplierPortalLoginRequest request,
            jakarta.servlet.http.HttpServletRequest http
    ) {
        return supplierPortalAuthService.login(request, http);
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
