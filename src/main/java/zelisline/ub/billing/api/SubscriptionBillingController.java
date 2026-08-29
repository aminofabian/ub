package zelisline.ub.billing.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.application.SubscriptionBillingService;
import zelisline.ub.billing.application.SubscriptionRenewalService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Tenant subscription billing — grace banner, renewal quote, M-Pesa STK
 * (SUBSCRIPTION_BILLING_SCOPE.md §12).
 */
@Validated
@RestController
@RequestMapping("/api/v1/subscription")
@RequiredArgsConstructor
public class SubscriptionBillingController {

    private final SubscriptionBillingService billingService;
    private final SubscriptionRenewalService renewalService;
    private final zelisline.ub.billing.application.SubscriptionPlanFitService planFitService;

    @GetMapping("/billing-status")
    public SubscriptionBillingDtos.BillingStatusResponse billingStatus(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return billingService.getBillingStatusView(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/plans")
    public SubscriptionBillingDtos.PlansResponse plans(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return new SubscriptionBillingDtos.PlansResponse(planFitService.activePlanResponses());
    }

    @GetMapping("/renewal-quote")
    @PreAuthorize("hasPermission(null, 'business.manage_subscription')")
    public SubscriptionBillingDtos.RenewalQuoteResponse renewalQuote(
            HttpServletRequest request,
            @RequestParam(required = false) String tier,
            @RequestParam(defaultValue = "1") int periodMonths
    ) {
        CurrentTenantUser.require(request);
        return renewalService.renewalQuote(
                TenantRequestIds.resolveBusinessId(request),
                tier,
                periodMonths);
    }

    @PostMapping("/renew")
    @PreAuthorize("hasPermission(null, 'business.manage_subscription')")
    public SubscriptionBillingDtos.RenewSubscriptionResponse renew(
            HttpServletRequest request,
            @Valid @RequestBody SubscriptionBillingDtos.RenewSubscriptionRequest body
    ) {
        CurrentTenantUser.require(request);
        return renewalService.initiate(TenantRequestIds.resolveBusinessId(request), body);
    }

    @GetMapping("/renewal-orders/{id}")
    @PreAuthorize("hasPermission(null, 'business.manage_subscription')")
    public SubscriptionBillingDtos.RenewalOrderStatusResponse renewalOrderStatus(
            HttpServletRequest request,
            @PathVariable String id
    ) {
        CurrentTenantUser.require(request);
        return renewalService.status(TenantRequestIds.resolveBusinessId(request), id);
    }
}
