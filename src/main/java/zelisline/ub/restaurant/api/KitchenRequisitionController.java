package zelisline.ub.restaurant.api;

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
import zelisline.ub.identity.application.RequestPermissionService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.IssueRequisitionRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RefuseRequisitionRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionListResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionProductResponse;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionRequest;
import zelisline.ub.restaurant.api.dto.RestaurantDtos.RequisitionResponse;
import zelisline.ub.restaurant.application.KitchenRequisitionService;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/**
 * Kitchen requisitions (ADR 0020, amendment 4). The kitchen asks; someone who looks after the
 * stock or the menu gives out or refuses. Scoped to the caller's business and, for someone tied
 * to a branch, to that branch.
 */
@Validated
@RestController
@RequestMapping("/api/v1/restaurant/requisitions")
@RequiredArgsConstructor
public class KitchenRequisitionController {

    /** Whoever keeps the store: someone who may change stock, or who runs the menu. */
    private static final String DECIDE =
            "hasPermission(null, 'inventory.write') or hasPermission(null, 'restaurant.menu.manage')";
    private static final String ASK = "hasPermission(null, 'restaurant.kitchen.update') or " + DECIDE;

    private final KitchenRequisitionService service;
    private final BranchResolutionService branchResolutionService;
    private final RequestPermissionService requestPermissionService;

    @GetMapping
    @PreAuthorize(ASK)
    public RequisitionListResponse list(@RequestParam String branchId, HttpServletRequest request) {
        return service.list(TenantRequestIds.resolveBusinessId(request), branch(request, branchId));
    }

    /** Products the kitchen can ask for. Same permission as asking; no prices in the answer. */
    @GetMapping("/products")
    @PreAuthorize(ASK)
    public List<RequisitionProductResponse> products(
            @RequestParam(required = false) String search, HttpServletRequest request) {
        return service.searchProducts(TenantRequestIds.resolveBusinessId(request), search);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(ASK)
    public RequisitionResponse ask(@Valid @RequestBody RequisitionRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return service.request(TenantRequestIds.resolveBusinessId(request), branch(request, body.branchId()),
                caller.userId(), body);
    }

    @PostMapping("/{id}/issue")
    @PreAuthorize(DECIDE)
    public RequisitionResponse issue(
            @PathVariable String id, @Valid @RequestBody(required = false) IssueRequisitionRequest body,
            HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        branch(request, service.branchOf(businessId, id));
        return service.issue(businessId, id, CurrentTenantUser.requireHuman(request).userId(), body);
    }

    @PostMapping("/{id}/refuse")
    @PreAuthorize(DECIDE)
    public RequisitionResponse refuse(
            @PathVariable String id, @Valid @RequestBody RefuseRequisitionRequest body, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        branch(request, service.branchOf(businessId, id));
        return service.refuse(businessId, id, CurrentTenantUser.requireHuman(request).userId(), body.reason());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize(ASK)
    public RequisitionResponse cancel(@PathVariable String id, HttpServletRequest request) {
        String businessId = TenantRequestIds.resolveBusinessId(request);
        branch(request, service.branchOf(businessId, id));
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        boolean canDecide = caller.roleId() != null
                && (requestPermissionService.hasPermission(caller.roleId(), "inventory.write")
                        || requestPermissionService.hasPermission(caller.roleId(), "restaurant.menu.manage"));
        return service.cancel(businessId, id, caller.userId(), canDecide);
    }

    /** The branch the caller may act on: the one asked for, unless their role ties them to another. */
    private String branch(HttpServletRequest request, String branchId) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), branchId);
    }
}
