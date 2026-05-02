package zelisline.ub.identity;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import zelisline.ub.platform.security.CurrentTenantUser;

/**
 * Test-only controller: two {@code hasPermission} checks in one SpEL expression
 * to assert the request-scoped permission cache performs a single JDBC read
 * (PHASE_1_PLAN.md §2.5 DoD). Imported explicitly from ITs — never registered in
 * production.
 */
@RestController
@RequestMapping("/api/v1/__test")
public class PermissionCacheProbeController {

    @GetMapping("/permission-cache-probe")
    @PreAuthorize("hasPermission(null, 'users.list') and hasPermission(null, 'catalog.items.read')")
    public String probe(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return "ok";
    }
}
