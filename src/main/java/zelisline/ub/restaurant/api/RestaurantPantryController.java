package zelisline.ub.restaurant.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.restaurant.api.dto.PantryDtos.LossRequest;
import zelisline.ub.restaurant.api.dto.PantryDtos.LossResponse;
import zelisline.ub.restaurant.api.dto.PantryDtos.PantryResponse;
import zelisline.ub.restaurant.api.dto.PantryDtos.PlanRequest;
import zelisline.ub.restaurant.api.dto.PantryDtos.PlanResponse;
import zelisline.ub.restaurant.application.PantryService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** The pantry (ADR 0026). Whoever runs the menu or the kitchen may read it, plan from it and log a loss. */
@RestController
@RequestMapping("/api/v1/restaurant/pantry")
@RequiredArgsConstructor
public class RestaurantPantryController {

    private static final String KITCHEN = "hasPermission(null, 'restaurant.kitchen.update')"
            + " or hasPermission(null, 'restaurant.menu.manage')";

    private final PantryService pantryService;
    private final BranchResolutionService branchResolutionService;

    @GetMapping
    @PreAuthorize(KITCHEN)
    public PantryResponse pantry(@RequestParam String branchId, HttpServletRequest request) {
        return pantryService.pantry(TenantRequestIds.resolveBusinessId(request), branch(request, branchId));
    }

    /** What a planned service needs bought. A calculation; nothing is stored. */
    @PostMapping("/plan")
    @PreAuthorize(KITCHEN)
    public PlanResponse plan(@Valid @RequestBody PlanRequest body, HttpServletRequest request) {
        return pantryService.plan(TenantRequestIds.resolveBusinessId(request), branch(request, body.branchId()), body);
    }

    /** Writes off an ingredient that spoiled, broke or expired. Not idempotent: one request, one write-off. */
    @PostMapping("/losses")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(KITCHEN)
    public LossResponse loss(@Valid @RequestBody LossRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return pantryService.recordLoss(TenantRequestIds.resolveBusinessId(request), branch(request, body.branchId()),
                caller.userId(), body);
    }

    private String branch(HttpServletRequest request, String branchId) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        if (branchId == null || branchId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose a branch");
        }
        return branchResolutionService.requireBranchForLockedRole(caller.roleId(), caller.branchId(), branchId.trim());
    }
}
