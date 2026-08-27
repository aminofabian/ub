package zelisline.ub.onboarding.sequence.api;

import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.onboarding.sequence.application.MerchantOnboardingSequenceService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@RestController
@RequestMapping("/api/v1/me/onboarding-sequence")
@RequiredArgsConstructor
public class MeOnboardingSequenceController {

    private final MerchantOnboardingSequenceService sequenceService;

    @GetMapping
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        boolean enrolled = sequenceService.isEnrolled(businessId);
        boolean muted = sequenceService.isMuted(businessId);
        return ResponseEntity.ok(Map.of(
                "enrolled", enrolled,
                "muted", muted));
    }

    @PostMapping("/mute")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<Map<String, Object>> mute(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        sequenceService.mute(businessId);
        return ResponseEntity.ok(Map.of("muted", true, "enrolled", sequenceService.isEnrolled(businessId)));
    }
}
