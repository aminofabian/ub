package zelisline.ub.serviceops.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
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
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.serviceops.api.dto.TeamDtos.AddTeamMemberRequest;
import zelisline.ub.serviceops.api.dto.TeamDtos.GiveLoginRequest;
import zelisline.ub.serviceops.api.dto.TeamDtos.MyEarningsResponse;
import zelisline.ub.serviceops.api.dto.TeamDtos.TeamMemberResponse;
import zelisline.ub.serviceops.application.ServiceTeamService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** The team of a service business as people who can sign in, and each person's own earnings. */
@Validated
@RestController
@RequestMapping("/api/v1/service-team")
@RequiredArgsConstructor
public class ServiceTeamController {

    private final ServiceTeamService serviceTeamService;
    private final zelisline.ub.payroll.application.StaffProfileService staffProfileService;

    /** Adds a team member. With an email and PIN they can sign in (needs {@code users.create} too). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'staff.hr.update')")
    public TeamMemberResponse add(@Valid @RequestBody AddTeamMemberRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return serviceTeamService.add(TenantRequestIds.resolveBusinessId(request), body, caller);
    }

    /** Gives an account to every team member added by name only. Safe to call again. */
    @PostMapping("/sync-accounts")
    @PreAuthorize("hasPermission(null, 'staff.hr.update') and hasPermission(null, 'users.create')")
    public zelisline.ub.serviceops.api.dto.TeamDtos.SyncAccountsResponse syncAccounts(HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return serviceTeamService.syncAccounts(TenantRequestIds.resolveBusinessId(request), caller);
    }

    @PostMapping("/{staffProfileId}/login")
    @PreAuthorize("hasPermission(null, 'staff.hr.update') and hasPermission(null, 'users.create')")
    public TeamMemberResponse giveLogin(
            @PathVariable String staffProfileId, @Valid @RequestBody GiveLoginRequest body, HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return serviceTeamService.giveLogin(TenantRequestIds.resolveBusinessId(request), staffProfileId, body, caller);
    }

    /** A day off, leave or a long break: the person cannot be booked for that time. */
    @PostMapping("/{staffProfileId}/time-off")
    @PreAuthorize("hasPermission(null, 'staff.hr.update')")
    public java.util.List<zelisline.ub.serviceops.domain.StaffTimeOff.Period> addTimeOff(
            @PathVariable String staffProfileId,
            @Valid @RequestBody zelisline.ub.posdraft.api.dto.ServiceTicketDtos.TimeOffRequest body,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return staffProfileService.addTimeOff(TenantRequestIds.resolveBusinessId(request), staffProfileId,
                body.from(), body.to(), body.reason());
    }

    @org.springframework.web.bind.annotation.DeleteMapping("/{staffProfileId}/time-off")
    @PreAuthorize("hasPermission(null, 'staff.hr.update')")
    public java.util.List<zelisline.ub.serviceops.domain.StaffTimeOff.Period> removeTimeOff(
            @PathVariable String staffProfileId,
            @org.springframework.web.bind.annotation.RequestParam java.time.Instant from,
            HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return staffProfileService.removeTimeOff(TenantRequestIds.resolveBusinessId(request), staffProfileId, from);
    }

    /** The caller's own earnings and bookings. Only ever their own. */
    @GetMapping("/me")
    @PreAuthorize("hasPermission(null, 'commissions.read_own') or hasPermission(null, 'commissions.read')")
    public MyEarningsResponse mine(HttpServletRequest request) {
        TenantPrincipal caller = CurrentTenantUser.requireHuman(request);
        return serviceTeamService.mine(TenantRequestIds.resolveBusinessId(request), caller.userId());
    }
}
