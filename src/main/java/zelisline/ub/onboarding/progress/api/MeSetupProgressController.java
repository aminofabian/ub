package zelisline.ub.onboarding.progress.api;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.onboarding.progress.api.dto.SetupProgressResponse;
import zelisline.ub.onboarding.progress.api.dto.SetupProgressSnoozeRequest;
import zelisline.ub.onboarding.progress.application.SetupProgressService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/me/setup-progress")
@RequiredArgsConstructor
public class MeSetupProgressController {

    private final SetupProgressService setupProgressService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public ResponseEntity<SetupProgressResponse> status(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return ResponseEntity.ok(setupProgressService.getForBusiness(businessId));
    }

    @PostMapping("/snooze")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public ResponseEntity<SetupProgressResponse> snooze(
            HttpServletRequest request,
            @RequestBody(required = false) SetupProgressSnoozeRequest body) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        Integer hours = body == null ? null : body.hours();
        return ResponseEntity.ok(setupProgressService.snooze(businessId, hours));
    }

    @PostMapping("/dismiss")
    @PreAuthorize("hasPermission(null, 'business.manage_settings')")
    public ResponseEntity<SetupProgressResponse> dismiss(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return ResponseEntity.ok(setupProgressService.dismiss(businessId));
    }
}
