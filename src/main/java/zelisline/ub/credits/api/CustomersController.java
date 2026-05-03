package zelisline.ub.credits.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.AddCustomerPhoneRequest;
import zelisline.ub.credits.api.dto.CreateCustomerRequest;
import zelisline.ub.credits.api.dto.CustomerResponse;
import zelisline.ub.credits.api.dto.IssuePaymentClaimResponse;
import zelisline.ub.credits.api.dto.PatchCustomerRequest;
import zelisline.ub.credits.api.dto.TopUpWalletRequest;
import zelisline.ub.credits.application.CreditCustomerStatementService;
import zelisline.ub.credits.application.CreditCustomerStatementService.CreditStatement;
import zelisline.ub.credits.application.CustomerDirectoryService;
import zelisline.ub.credits.application.PublicPaymentClaimService;
import zelisline.ub.credits.application.PublicPaymentClaimService.IssuedClaimToken;
import zelisline.ub.credits.application.WalletLedgerService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/customers")
@RequiredArgsConstructor
public class CustomersController {

    private final CustomerDirectoryService customerDirectoryService;
    private final CreditCustomerStatementService creditCustomerStatementService;
    private final WalletLedgerService walletLedgerService;
    private final PublicPaymentClaimService publicPaymentClaimService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public Page<CustomerResponse> list(
            Pageable pageable,
            @RequestParam(required = false) String phone,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return customerDirectoryService.list(TenantRequestIds.resolveBusinessId(request), phone, pageable);
    }

    @GetMapping("/{customerId}")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public CustomerResponse get(@PathVariable String customerId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String resolved = customerDirectoryService.resolveCustomerIdOrThrow(businessId, customerId);
        return customerDirectoryService.get(businessId, resolved);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse create(@Valid @RequestBody CreateCustomerRequest body, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return customerDirectoryService.create(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{customerId}")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerResponse patch(
            @PathVariable String customerId,
            @Valid @RequestBody PatchCustomerRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String resolved = customerDirectoryService.resolveCustomerIdOrThrow(businessId, customerId);
        return customerDirectoryService.patch(businessId, resolved, body);
    }

    @DeleteMapping("/{customerId}")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String customerId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String resolved = customerDirectoryService.resolveCustomerIdOrThrow(businessId, customerId);
        customerDirectoryService.softDelete(businessId, resolved);
    }

    @PostMapping("/{customerId}/phones")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public CustomerResponse addPhone(
            @PathVariable String customerId,
            @Valid @RequestBody AddCustomerPhoneRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String resolved = customerDirectoryService.resolveCustomerIdOrThrow(businessId, customerId);
        return customerDirectoryService.addPhone(businessId, resolved, body);
    }

    @PostMapping("/{customerId}/phones/{phoneId}/set-primary")
    @PreAuthorize("hasPermission(null, 'credits.customers.write')")
    public CustomerResponse setPrimary(
            @PathVariable String customerId,
            @PathVariable String phoneId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String resolved = customerDirectoryService.resolveCustomerIdOrThrow(businessId, customerId);
        return customerDirectoryService.setPrimaryPhone(
                businessId, resolved, phoneId);
    }

    @GetMapping("/{customerId}/credit-statement")
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public CreditStatement creditStatement(@PathVariable String customerId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String resolved = customerDirectoryService.resolveCustomerIdOrThrow(businessId, customerId);
        return creditCustomerStatementService.assemble(businessId, resolved);
    }

    @PostMapping("/{customerId}/wallet/top-ups")
    @PreAuthorize("hasPermission(null, 'credits.wallet.write')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void topUpWallet(
            @PathVariable String customerId,
            @Valid @RequestBody TopUpWalletRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String resolved = customerDirectoryService.resolveCustomerIdOrThrow(businessId, customerId);
        walletLedgerService.topUpCashAtCounter(
                businessId,
                resolved,
                body.amount());
    }

    @PostMapping("/{customerId}/payment-claims")
    @PreAuthorize("hasPermission(null, 'credits.claims.issue')")
    @ResponseStatus(HttpStatus.CREATED)
    public IssuePaymentClaimResponse issuePaymentClaim(
            @PathVariable String customerId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String resolved = customerDirectoryService.resolveCustomerIdOrThrow(businessId, customerId);
        IssuedClaimToken token =
                publicPaymentClaimService.issueClaim(businessId, resolved);
        return new IssuePaymentClaimResponse(token.claimId(), token.plaintextToken());
    }
}
