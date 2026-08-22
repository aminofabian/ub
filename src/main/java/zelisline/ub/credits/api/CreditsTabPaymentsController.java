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
import zelisline.ub.credits.api.dto.AmendTabPaymentRequest;
import zelisline.ub.credits.api.dto.RecordTabPaymentRequest;
import zelisline.ub.credits.api.dto.RecordTabPaymentResponse;
import zelisline.ub.credits.api.dto.ReverseTabPaymentRequest;
import zelisline.ub.credits.application.PublicPaymentClaimService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/credits/tab-payments")
@RequiredArgsConstructor
public class CreditsTabPaymentsController {

    private final PublicPaymentClaimService publicPaymentClaimService;

    /**
     * Record cash/M-Pesa toward an open tab and clear debt immediately (partial or full).
     * Requires claims review — same bar as approving a payment claim.
     */
    @PostMapping
    @PreAuthorize("hasPermission(null, 'credits.claims.review')")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordTabPaymentResponse record(
            @Valid @RequestBody RecordTabPaymentRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return publicPaymentClaimService.recordAdminTabPayment(
                TenantRequestIds.resolveBusinessId(request),
                body,
                CurrentTenantUser.auditActorId(request)
        );
    }

    /**
     * Restore the balance owed after a mistaken tab payment (reverses the most recent
     * payment on the account). Requires claims review — same bar as recording payments.
     */
    @PostMapping("/reverse")
    @PreAuthorize("hasPermission(null, 'credits.claims.review')")
    public RecordTabPaymentResponse reverse(
            @Valid @RequestBody ReverseTabPaymentRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return publicPaymentClaimService.reverseTabPayment(
                TenantRequestIds.resolveBusinessId(request),
                body.customerId()
        );
    }

    /**
     * Reverse a mistaken tab payment and record the corrected amount in one step.
     * Requires claims review — same bar as recording payments.
     */
    @PostMapping("/amend")
    @PreAuthorize("hasPermission(null, 'credits.claims.review')")
    public RecordTabPaymentResponse amend(
            @Valid @RequestBody AmendTabPaymentRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return publicPaymentClaimService.amendTabPayment(
                TenantRequestIds.resolveBusinessId(request),
                body,
                CurrentTenantUser.auditActorId(request)
        );
    }
}
