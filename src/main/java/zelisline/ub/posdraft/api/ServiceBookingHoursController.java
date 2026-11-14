package zelisline.ub.posdraft.api;

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
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.BookingHoursResponse;
import zelisline.ub.posdraft.api.dto.OnlineBookingDtos.SetBookingHoursRequest;
import zelisline.ub.posdraft.application.OnlineBookingService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** When customers may book online at a branch. Setting hours switches online booking on. */
@Validated
@RestController
@RequestMapping("/api/v1/service-booking-hours")
@RequiredArgsConstructor
public class ServiceBookingHoursController {

    private final OnlineBookingService onlineBookingService;
    private final zelisline.ub.tenancy.application.BranchResolutionService branchResolutionService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'sales.sell') or hasPermission(null, 'services.catalog.manage')")
    public BookingHoursResponse get(@RequestParam String branchId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return onlineBookingService.hours(TenantRequestIds.resolveBusinessId(request), branchId);
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'services.catalog.manage')")
    public BookingHoursResponse set(@Valid @RequestBody SetBookingHoursRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        String branch = branchResolutionService.requireBranchForLockedRole(
                caller.roleId(), caller.branchId(), body.branchId());
        return onlineBookingService.setHours(TenantRequestIds.resolveBusinessId(request),
                new SetBookingHoursRequest(branch, body.days()), caller.userId());
    }
}
