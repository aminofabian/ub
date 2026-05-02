package zelisline.ub.tenancy.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.dto.BranchResponse;
import zelisline.ub.tenancy.api.dto.CreateBranchRequest;
import zelisline.ub.tenancy.api.dto.PatchBranchRequest;
import zelisline.ub.tenancy.application.TenancyService;

@Validated
@RestController
@RequestMapping("/api/v1/branches")
@RequiredArgsConstructor
public class BranchesController {

    private final TenancyService tenancyService;

    @GetMapping
    public Page<BranchResponse> list(Pageable pageable, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return tenancyService.listBranches(TenantRequestIds.resolveBusinessId(request), pageable);
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    @ResponseStatus(HttpStatus.CREATED)
    public BranchResponse create(
            @Valid @RequestBody CreateBranchRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tenancyService.createBranch(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PatchMapping("/{branchId}")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public BranchResponse patch(
            @PathVariable String branchId,
            @Valid @RequestBody PatchBranchRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return tenancyService.patchBranch(TenantRequestIds.resolveBusinessId(request), branchId, body);
    }
}
