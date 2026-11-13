package zelisline.ub.payroll.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import zelisline.ub.payroll.api.dto.CreateStaffProfileRequest;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payroll.api.dto.StaffProfileResponse;
import zelisline.ub.payroll.api.dto.UpdateStaffProfileRequest;
import zelisline.ub.payroll.application.StaffProfileService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/staff")
@RequiredArgsConstructor
public class StaffProfileController {

    private final StaffProfileService staffProfileService;

    @GetMapping("/profiles")
    @PreAuthorize("hasPermission(null, 'staff.profile.read')")
    public List<StaffProfileResponse> listProfiles(HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        return staffProfileService.listProfiles(TenantRequestIds.resolveBusinessId(request), user);
    }

    /** Add a staff member who has no login (for example a barber). */
    @PostMapping("/profiles")
    @PreAuthorize("hasPermission(null, 'staff.hr.update')")
    @ResponseStatus(HttpStatus.CREATED)
    public StaffProfileResponse createProfile(
            @Valid @RequestBody CreateStaffProfileRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return staffProfileService.createWithoutLogin(
                TenantRequestIds.resolveBusinessId(request),
                body,
                user
        );
    }

    /** Update a staff profile by its own id; the only route to one that has no login. */
    @PatchMapping("/profiles/{profileId}")
    @PreAuthorize("hasPermission(null, 'staff.hr.update')")
    public StaffProfileResponse updateProfileById(
            @PathVariable String profileId,
            @Valid @RequestBody UpdateStaffProfileRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return staffProfileService.updateProfileById(
                TenantRequestIds.resolveBusinessId(request),
                profileId,
                body,
                user
        );
    }

    @GetMapping("/{userId}/profile")
    @PreAuthorize("hasPermission(null, 'staff.profile.read')")
    public StaffProfileResponse getProfile(@PathVariable String userId, HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        return staffProfileService.getOrCreateProfile(
                TenantRequestIds.resolveBusinessId(request),
                userId,
                user
        );
    }

    @PatchMapping("/{userId}/profile")
    @PreAuthorize("hasPermission(null, 'staff.hr.update')")
    public StaffProfileResponse updateProfile(
            @PathVariable String userId,
            @Valid @RequestBody UpdateStaffProfileRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return staffProfileService.updateProfile(
                TenantRequestIds.resolveBusinessId(request),
                userId,
                body,
                user
        );
    }
}
