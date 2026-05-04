package zelisline.ub.credits.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.ApproveClaimRequest;
import zelisline.ub.credits.api.dto.RejectClaimRequest;
import zelisline.ub.credits.application.PublicPaymentClaimService;
import zelisline.ub.credits.domain.PublicPaymentClaim;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/credits/payment-claims")
@RequiredArgsConstructor
public class CreditsPaymentClaimsController {

    private final PublicPaymentClaimService publicPaymentClaimService;

    @GetMapping("/submitted")
    @PreAuthorize("hasPermission(null, 'credits.claims.review')")
    public List<PublicPaymentClaim> listSubmitted(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return publicPaymentClaimService.listSubmitted(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/{claimId}/approve")
    @PreAuthorize("hasPermission(null, 'credits.claims.review')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void approve(
            @PathVariable String claimId,
            @Valid @RequestBody ApproveClaimRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        publicPaymentClaimService.approve(TenantRequestIds.resolveBusinessId(request), claimId, body.channel());
    }

    @PostMapping("/{claimId}/reject")
    @PreAuthorize("hasPermission(null, 'credits.claims.review')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reject(
            @PathVariable String claimId,
            @Valid @RequestBody RejectClaimRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        publicPaymentClaimService.reject(
                TenantRequestIds.resolveBusinessId(request),
                claimId,
                body == null ? null : body.reason());
    }
}
