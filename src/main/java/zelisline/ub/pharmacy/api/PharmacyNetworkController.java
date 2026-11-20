package zelisline.ub.pharmacy.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.pharmacy.api.dto.PharmacyNetworkDtos;
import zelisline.ub.pharmacy.application.OrderingService;
import zelisline.ub.pharmacy.application.SchemeClaimService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/** Ordering forecast, central purchasing and scheme claims (ADR 0033, Phase 3). */
@Validated
@RestController
@RequestMapping("/api/v1/pharmacy")
@RequiredArgsConstructor
public class PharmacyNetworkController {

    private final OrderingService orderingService;
    private final SchemeClaimService schemeClaimService;
    private final BranchResolutionService branchResolutionService;

    // ── Ordering ──────────────────────────────────────────────────────

    /** {@code branchId} absent means every branch; a branch-tied role always gets its own branch. */
    @GetMapping("/ordering/forecast")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public PharmacyNetworkDtos.Forecast forecast(
            @RequestParam(required = false) String branchId, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        return orderingService.forecast(businessId, locked != null ? locked : branchId);
    }

    @GetMapping("/ordering/settings")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public PharmacyNetworkDtos.OrderingSettings orderingSettings(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return orderingService.settings(TenantRequestIds.resolveBusinessId(request));
    }

    @PutMapping("/ordering/settings")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    public PharmacyNetworkDtos.OrderingSettings saveOrderingSettings(
            @Valid @RequestBody PharmacyNetworkDtos.OrderingSettings body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return orderingService.saveSettings(TenantRequestIds.resolveBusinessId(request), body);
    }

    /** A draft order at one branch for several branches' needs. A branch-tied role may only order for its own. */
    @PostMapping("/ordering/orders")
    @PreAuthorize("hasPermission(null, 'purchasing.path_a.write')")
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyNetworkDtos.CentralOrderResponse createOrder(
            @Valid @RequestBody PharmacyNetworkDtos.CentralOrderRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        if (locked != null) {
            boolean ownOnly = locked.equals(body.receivingBranchId().strip())
                    && body.lines().stream()
                            .flatMap(l -> l.shares().stream())
                            .allMatch(s -> locked.equals(s.branchId().strip()));
            if (!ownOnly) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN, "You can only order for your own branch");
            }
        }
        return orderingService.createCentralOrder(businessId, body, user.userId());
    }

    @GetMapping("/ordering/splits")
    @PreAuthorize("hasPermission(null, 'inventory.read')")
    public List<PharmacyNetworkDtos.PendingSplit> pendingSplits(HttpServletRequest request) {
        requireAllBranches(CurrentTenantUser.requireHuman(request));
        return orderingService.pendingSplits(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/ordering/splits/{purchaseOrderId}/transfers")
    @PreAuthorize("hasPermission(null, 'inventory.write')")
    public PharmacyNetworkDtos.SplitResult draftTransfers(
            @PathVariable String purchaseOrderId, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        requireAllBranches(user);
        return orderingService.draftTransfers(
                TenantRequestIds.resolveBusinessId(request), purchaseOrderId, user.userId());
    }

    // ── Schemes and claims ────────────────────────────────────────────

    @GetMapping("/schemes")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read') or hasPermission(null, 'pharmacy.dispense')")
    public List<PharmacyNetworkDtos.SchemeResponse> schemes(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return schemeClaimService.schemes(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/schemes")
    @PreAuthorize("hasPermission(null, 'pharmacy.claims.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyNetworkDtos.SchemeResponse createScheme(
            @Valid @RequestBody PharmacyNetworkDtos.SchemeRequest body, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return schemeClaimService.saveScheme(TenantRequestIds.resolveBusinessId(request), null, body);
    }

    @PutMapping("/schemes/{schemeId}")
    @PreAuthorize("hasPermission(null, 'pharmacy.claims.manage')")
    public PharmacyNetworkDtos.SchemeResponse updateScheme(
            @PathVariable String schemeId,
            @Valid @RequestBody PharmacyNetworkDtos.SchemeRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return schemeClaimService.saveScheme(TenantRequestIds.resolveBusinessId(request), schemeId, body);
    }

    @GetMapping("/claims")
    @PreAuthorize("hasPermission(null, 'pharmacy.records.read')")
    public List<PharmacyNetworkDtos.ClaimResponse> claims(HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return schemeClaimService.claims(businessId, lockedBranch(user, businessId), user.userId());
    }

    @PostMapping("/claims")
    @PreAuthorize("hasPermission(null, 'pharmacy.dispense') or hasPermission(null, 'pharmacy.claims.manage')")
    @ResponseStatus(HttpStatus.CREATED)
    public PharmacyNetworkDtos.ClaimResponse createClaim(
            @Valid @RequestBody PharmacyNetworkDtos.ClaimRequest body, HttpServletRequest request) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return schemeClaimService.createClaim(businessId, body, user.userId(), lockedBranch(user, businessId));
    }

    @PostMapping("/claims/{claimId}/status")
    @PreAuthorize("hasPermission(null, 'pharmacy.claims.manage')")
    public PharmacyNetworkDtos.ClaimResponse setClaimStatus(
            @PathVariable String claimId,
            @Valid @RequestBody PharmacyNetworkDtos.ClaimStatusRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        return schemeClaimService.setStatus(TenantRequestIds.resolveBusinessId(request), claimId, body, user.userId());
    }

    // ── Shared ────────────────────────────────────────────────────────

    private String lockedBranch(TenantPrincipal user, String businessId) {
        if (!branchResolutionService.isBranchLockedRole(user.roleId())) {
            return null;
        }
        return branchResolutionService.resolveBranchForReport(businessId, user.roleId(), user.branchId(), null);
    }

    private void requireAllBranches(TenantPrincipal user) {
        if (branchResolutionService.isBranchLockedRole(user.roleId())) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN, "Sharing a delivery between branches is for staff who work across branches");
        }
    }
}
