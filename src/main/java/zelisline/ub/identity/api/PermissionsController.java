package zelisline.ub.identity.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.api.dto.PermissionResponse;
import zelisline.ub.identity.application.IdentityService;
import zelisline.ub.platform.security.CurrentTenantUser;

@RestController
@RequestMapping("/api/v1/permissions")
@RequiredArgsConstructor
public class PermissionsController {

    private final IdentityService identityService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public List<PermissionResponse> listPermissions(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return identityService.listPermissions();
    }
}
