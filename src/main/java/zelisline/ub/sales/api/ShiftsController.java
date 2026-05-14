package zelisline.ub.sales.api;

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
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.repository.RoleRepository;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.sales.api.dto.PostCloseShiftRequest;
import zelisline.ub.sales.api.dto.PostOpenShiftRequest;
import zelisline.ub.sales.api.dto.ShiftDetailResponse;
import zelisline.ub.sales.api.dto.ShiftListResponse;
import zelisline.ub.sales.api.dto.ShiftResponse;
import zelisline.ub.sales.application.ShiftService;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/shifts")
@RequiredArgsConstructor
public class ShiftsController {

    private final ShiftService shiftService;
    private final RoleRepository roleRepository;

    @PostMapping("/open")
    @PreAuthorize("hasPermission(null, 'shifts.open')")
    @ResponseStatus(HttpStatus.CREATED)
    public ShiftResponse openShift(@Valid @RequestBody PostOpenShiftRequest body, HttpServletRequest request) {
        var user = CurrentTenantUser.requireHuman(request);
        return shiftService.openShift(TenantRequestIds.resolveBusinessId(request), body, user.userId());
    }

    @GetMapping("/current")
    @PreAuthorize("hasPermission(null, 'shifts.read')")
    public ShiftResponse currentShift(@RequestParam @NotBlank String branchId, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return shiftService.getCurrentOpenShift(
                TenantRequestIds.resolveBusinessId(request),
                branchId
        );
    }

    @PostMapping("/{shiftId}/close")
    @PreAuthorize("hasPermission(null, 'shifts.close')")
    public ShiftResponse closeShift(
            @PathVariable String shiftId,
            @Valid @RequestBody PostCloseShiftRequest body,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        return shiftService.closeShift(
                TenantRequestIds.resolveBusinessId(request),
                shiftId,
                body,
                user.userId()
        );
    }

    @GetMapping
    @PreAuthorize("hasPermission(null, 'shifts.read')")
    public ShiftListResponse listShifts(
            @RequestParam(required = false) String branchId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String openedBy,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        String effectiveOpenedBy = openedBy;

        var role = roleRepository.findByIdAndDeletedAtIsNull(user.roleId()).orElse(null);
        if (role != null && "cashier".equals(role.getRoleKey())) {
            effectiveOpenedBy = user.userId();
        }

        return shiftService.listShifts(
                TenantRequestIds.resolveBusinessId(request),
                branchId,
                status,
                effectiveOpenedBy,
                page,
                size
        );
    }

    @GetMapping("/{shiftId}")
    @PreAuthorize("hasPermission(null, 'shifts.read')")
    public ShiftDetailResponse getShiftDetail(
            @PathVariable String shiftId,
            HttpServletRequest request
    ) {
        var user = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);

        ShiftDetailResponse detail = shiftService.getShiftDetail(businessId, shiftId);

        var role = roleRepository.findByIdAndDeletedAtIsNull(user.roleId()).orElse(null);
        if (role != null && "cashier".equals(role.getRoleKey())) {
            if (!user.userId().equals(detail.openedBy())) {
                throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                        "Cashiers can only view their own shifts");
            }
        }

        return detail;
    }
}
