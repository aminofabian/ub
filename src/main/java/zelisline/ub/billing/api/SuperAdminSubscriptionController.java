package zelisline.ub.billing.api;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.billing.api.dto.SubscriptionBillingDtos;
import zelisline.ub.billing.application.SubscriptionBillingService;
import zelisline.ub.billing.application.SubscriptionBillingSettingsService;
import zelisline.ub.billing.application.SubscriptionDunningAnalyticsService;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin")
@RequiredArgsConstructor
public class SuperAdminSubscriptionController {

    private final SubscriptionBillingSettingsService settingsService;
    private final SubscriptionBillingService billingService;
    private final SubscriptionDunningAnalyticsService dunningAnalyticsService;
    private final SuperAdminRepository superAdminRepository;

    @GetMapping("/platform/subscription/settings")
    public SubscriptionBillingDtos.SettingsResponse getSettings() {
        requireSuperAdmin();
        return settingsService.getSettings();
    }

    @PatchMapping("/platform/subscription/settings")
    public SubscriptionBillingDtos.SettingsResponse updateSettings(
            @Valid @RequestBody SubscriptionBillingDtos.UpdateSettingsRequest body
    ) {
        requireSuperAdmin();
        return settingsService.updateSettings(body);
    }

    @GetMapping("/platform/subscription/plans")
    public SubscriptionBillingDtos.PlansResponse getPlans() {
        requireSuperAdmin();
        return settingsService.getPlans();
    }

    @PutMapping("/platform/subscription/plans/{tierCode}")
    public SubscriptionBillingDtos.PlansResponse upsertPlan(
            @PathVariable String tierCode,
            @Valid @RequestBody SubscriptionBillingDtos.UpdatePlanRequest body
    ) {
        requireSuperAdmin();
        return settingsService.upsertPlan(tierCode, body);
    }

    @GetMapping("/businesses/{businessId}/subscription")
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot businessSubscription(
            @PathVariable String businessId
    ) {
        requireSuperAdmin();
        return billingService.adminSnapshot(businessId);
    }

    @PostMapping("/businesses/{businessId}/subscription/extend")
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot extendSubscription(
            @PathVariable String businessId,
            @Valid @RequestBody SubscriptionBillingDtos.ExtendSubscriptionRequest body
    ) {
        SuperAdmin admin = requireSuperAdmin();
        return billingService.extendPeriod(businessId, body.months(), body.note(), admin.getId());
    }

    @PostMapping("/businesses/{businessId}/subscription/extend-grace")
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot extendGrace(
            @PathVariable String businessId,
            @Valid @RequestBody SubscriptionBillingDtos.ExtendGraceRequest body
    ) {
        SuperAdmin admin = requireSuperAdmin();
        return billingService.extendGrace(businessId, body.days(), body.note(), admin.getId());
    }

    @PostMapping("/businesses/{businessId}/subscription/plan")
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot assignPlan(
            @PathVariable String businessId,
            @Valid @RequestBody SubscriptionBillingDtos.AssignPlanRequest body
    ) {
        SuperAdmin admin = requireSuperAdmin();
        return billingService.assignPlan(businessId, body.tierCode(), body.note(), admin.getId());
    }

    @PatchMapping("/businesses/{businessId}/subscription")
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot overrideSubscription(
            @PathVariable String businessId,
            @Valid @RequestBody SubscriptionBillingDtos.OverrideSubscriptionRequest body
    ) {
        SuperAdmin admin = requireSuperAdmin();
        return billingService.override(businessId, body, admin.getId());
    }

    @PostMapping("/businesses/{businessId}/subscription/reactivate")
    public SubscriptionBillingDtos.AdminSubscriptionSnapshot reactivateSubscription(
            @PathVariable String businessId
    ) {
        SuperAdmin admin = requireSuperAdmin();
        return billingService.reactivate(businessId, admin.getId());
    }

    @GetMapping("/platform/subscription/dunning")
    public SubscriptionBillingDtos.DunningAnalyticsResponse dunningAnalytics() {
        requireSuperAdmin();
        return dunningAnalyticsService.snapshot();
    }

    private SuperAdmin requireSuperAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        String id = (String) authentication.getPrincipal();
        return superAdminRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Super admin not found"));
    }
}
