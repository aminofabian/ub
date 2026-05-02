package zelisline.ub.identity.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.UpdateMeRequest;
import zelisline.ub.identity.api.dto.UserResponse;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/me")
@RequiredArgsConstructor
public class IdentityMeController {

    private final IdentityService identityService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public UserResponse getMe(HttpServletRequest request) {
        var principal = CurrentTenantUser.require(request);
        return identityService.getMe(TenantRequestIds.resolveBusinessId(request), principal.userId());
    }

    @PatchMapping
    @PreAuthorize("isAuthenticated()")
    public UserResponse updateMe(
            @Valid @RequestBody UpdateMeRequest body,
            HttpServletRequest request
    ) {
        var principal = CurrentTenantUser.require(request);
        return identityService.updateMe(TenantRequestIds.resolveBusinessId(request), principal.userId(), body);
    }
}
