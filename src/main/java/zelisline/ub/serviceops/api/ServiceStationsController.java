package zelisline.ub.serviceops.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.tenancy.api.TenantRequestIds;
import zelisline.ub.tenancy.application.BranchResolutionService;
import zelisline.ub.tenancy.application.BranchStationService;

/**
 * Chairs (stations, rooms) of a branch: numbered 1..N, so the only thing stored is how many
 * there are (ADR 0014 amendment). What a chair is called is wording on the client.
 */
@Validated
@RestController
@RequestMapping("/api/v1/service-stations")
@RequiredArgsConstructor
public class ServiceStationsController {

    private final BranchStationService branchStationService;
    private final BranchResolutionService branchResolutionService;

    public record StationsResponse(String branchId, int count) {
    }

    public record SetStationsRequest(
            @NotBlank String branchId,
            @NotNull @Min(0) @Max(BranchStationService.MAX_STATIONS) Integer count
    ) {
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')")
    public StationsResponse get(@RequestParam String branchId, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return new StationsResponse(branchId,
                branchStationService.stationCount(TenantRequestIds.resolveBusinessId(request), branchId));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public StationsResponse set(@Valid @RequestBody SetStationsRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        // A branch-locked role may only change its own branch.
        String branchId = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), body.branchId());
        int count = branchStationService.setStationCount(
                TenantRequestIds.resolveBusinessId(request), branchId, body.count());
        return new StationsResponse(branchId, count);
    }
}
