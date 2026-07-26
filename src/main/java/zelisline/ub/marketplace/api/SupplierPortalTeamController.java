package zelisline.ub.marketplace.api;

import java.util.List;

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

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.marketplace.api.dto.CreateSupplierPortalTeamUserRequest;
import zelisline.ub.marketplace.api.dto.PatchSupplierPortalTeamUserRequest;
import zelisline.ub.marketplace.api.dto.ResetSupplierPortalTeamUserPasswordRequest;
import zelisline.ub.marketplace.api.dto.SupplierPortalTeamUserRow;
import zelisline.ub.marketplace.application.SupplierPortalTeamService;
import zelisline.ub.platform.security.CurrentSupplierUser;
import zelisline.ub.platform.security.SupplierPrincipal;

@Validated
@RestController
@RequestMapping("/api/v1/supplier-portal/team")
@RequiredArgsConstructor
public class SupplierPortalTeamController {

    private final SupplierPortalTeamService teamService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'supplier.team.manage')")
    public List<SupplierPortalTeamUserRow> list() {
        SupplierPrincipal principal = CurrentSupplierUser.require();
        return teamService.list(principal);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'supplier.team.manage')")
    public SupplierPortalTeamUserRow create(@Valid @RequestBody CreateSupplierPortalTeamUserRequest request) {
        return teamService.create(CurrentSupplierUser.require(), request);
    }

    @PatchMapping("/{userId}")
    @PreAuthorize("hasPermission(null, 'supplier.team.manage')")
    public SupplierPortalTeamUserRow patch(
            @PathVariable String userId,
            @Valid @RequestBody PatchSupplierPortalTeamUserRequest request) {
        return teamService.patch(CurrentSupplierUser.require(), userId, request);
    }

    @PostMapping("/{userId}/reset-password")
    @PreAuthorize("hasPermission(null, 'supplier.team.manage')")
    public SupplierPortalTeamUserRow resetPassword(
            @PathVariable String userId,
            @Valid @RequestBody ResetSupplierPortalTeamUserPasswordRequest request) {
        return teamService.resetPassword(CurrentSupplierUser.require(), userId, request.password());
    }
}
