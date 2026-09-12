package zelisline.ub.marketplace.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
import zelisline.ub.marketplace.api.dto.CreateMarketplaceEscrowHoldRequest;
import zelisline.ub.marketplace.api.dto.MarketplaceEscrowHoldResponse;
import zelisline.ub.marketplace.application.MarketplaceEscrowService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/marketplace/escrow-holds")
@RequiredArgsConstructor
public class MarketplaceEscrowController {

    private final MarketplaceEscrowService escrowService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public List<MarketplaceEscrowHoldResponse> list(
            HttpServletRequest request,
            @RequestParam(defaultValue = "20") int limit
    ) {
        CurrentTenantUser.require(request);
        return escrowService.listForBusiness(TenantRequestIds.resolveBusinessId(request), limit);
    }

    @GetMapping("/{holdId}")
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public MarketplaceEscrowHoldResponse get(HttpServletRequest request, @PathVariable String holdId) {
        CurrentTenantUser.require(request);
        return escrowService.get(TenantRequestIds.resolveBusinessId(request), holdId);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public MarketplaceEscrowHoldResponse create(
            HttpServletRequest request,
            @Valid @RequestBody CreateMarketplaceEscrowHoldRequest body
    ) {
        CurrentTenantUser.require(request);
        return escrowService.createHold(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PostMapping("/{holdId}/cancel")
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public MarketplaceEscrowHoldResponse cancel(HttpServletRequest request, @PathVariable String holdId) {
        CurrentTenantUser.require(request);
        return escrowService.cancel(TenantRequestIds.resolveBusinessId(request), holdId);
    }

    @PostMapping("/{holdId}/release")
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public MarketplaceEscrowHoldResponse release(HttpServletRequest request, @PathVariable String holdId) {
        CurrentTenantUser.require(request);
        return escrowService.releaseManual(
                TenantRequestIds.resolveBusinessId(request),
                holdId,
                null,
                false);
    }
}
