package zelisline.ub.pharmacy.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.pharmacy.api.dto.TillMedicineDtos.GenericMatch;
import zelisline.ub.pharmacy.api.dto.TillMedicineDtos.TillMedicinesRequest;
import zelisline.ub.pharmacy.api.dto.TillMedicineDtos.TillMedicinesResponse;
import zelisline.ub.pharmacy.application.DispensingGate;
import zelisline.ub.pharmacy.application.TillMedicineService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;

/**
 * What the till shows about medicines while a sale is being built (ADR 0033, "Catalogue and
 * till"). Read only and scoped to the caller's business; a branch-tied role only ever sees its
 * own branch's lots.
 */
@Validated
@RestController
@RequestMapping("/api/v1/pharmacy/till")
@RequiredArgsConstructor
public class PharmacyTillController {

    private final TillMedicineService tillMedicineService;
    private final DispensingGate dispensingGate;
    private final BranchResolutionService branchResolutionService;

    @PostMapping("/medicines")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public TillMedicinesResponse medicines(
            @Valid @RequestBody TillMedicinesRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        String locked = lockedBranch(user, businessId);
        String branchId = locked != null ? locked : body.branchId();
        return new TillMedicinesResponse(
                tillMedicineService.shelf(businessId, branchId, body.itemIds()),
                dispensingGate.mayDispense(businessId, user.userId(), user.roleId()));
    }

    /** Medicines whose generic name contains {@code q}; empty for fewer than three characters. */
    @GetMapping("/generic")
    @PreAuthorize("hasPermission(null, 'sales.sell')")
    public List<GenericMatch> generic(@RequestParam String q, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return tillMedicineService.searchGeneric(TenantRequestIds.resolveBusinessId(request), q);
    }

    private String lockedBranch(TenantPrincipal user, String businessId) {
        if (!branchResolutionService.isBranchLockedRole(user.roleId())) {
            return null;
        }
        return branchResolutionService.resolveBranchForReport(businessId, user.roleId(), user.branchId(), null);
    }
}
