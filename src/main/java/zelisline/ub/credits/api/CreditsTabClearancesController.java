package zelisline.ub.credits.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.ProposeTabClearanceRequest;
import zelisline.ub.credits.api.dto.ProposeTabClearanceResponse;
import zelisline.ub.credits.application.PublicPaymentClaimService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/credits/tab-clearances")
@RequiredArgsConstructor
public class CreditsTabClearancesController {

    private final PublicPaymentClaimService publicPaymentClaimService;

    @PostMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    @ResponseStatus(HttpStatus.CREATED)
    public ProposeTabClearanceResponse propose(
            @Valid @RequestBody ProposeTabClearanceRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String claimId = publicPaymentClaimService.proposeCashierTabClearance(
                businessId,
                body,
                CurrentTenantUser.auditActorId(request)
        );
        return new ProposeTabClearanceResponse(claimId);
    }
}
