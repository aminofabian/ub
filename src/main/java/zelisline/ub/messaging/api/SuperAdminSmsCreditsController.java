package zelisline.ub.messaging.api;

import java.util.List;

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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.messaging.api.dto.PlatformSmsCreditSettingsDtos;
import zelisline.ub.messaging.api.dto.SmsCreditAdminDtos;
import zelisline.ub.messaging.api.dto.SmsCreditLedgerResponse;
import zelisline.ub.messaging.api.dto.SmsCreditPurchaseDtos;
import zelisline.ub.messaging.api.dto.SmsCreditUsageResponse;
import zelisline.ub.messaging.application.SmsCreditPurchaseService;
import zelisline.ub.messaging.application.SmsCreditService;
import zelisline.ub.messaging.application.SmsCreditSettingsService;
import zelisline.ub.messaging.domain.BusinessSmsCreditAccount;
import zelisline.ub.messaging.domain.SmsCreditLedgerEntry;
import zelisline.ub.messaging.domain.SmsCreditPurchase;

/**
 * Super Admin SMS credits management: global price/limits/kill switch, tier
 * allowance table, per-business grants + override + drill-down
 * (SMS_CREDITS_SCOPE.md §10, §11).
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin")
@RequiredArgsConstructor
public class SuperAdminSmsCreditsController {

    private final SmsCreditSettingsService settingsService;
    private final SmsCreditService creditService;
    private final SmsCreditPurchaseService purchaseService;
    private final SuperAdminRepository superAdminRepository;
    private final zelisline.ub.tenancy.repository.BusinessRepository businessRepository;

    // ── Global settings & tier allowances ──────────────────────────────

    @GetMapping("/platform/sms-credits/settings")
    public PlatformSmsCreditSettingsDtos.SettingsResponse getSettings() {
        requireSuperAdmin();
        return settingsService.getSettings();
    }

    @PatchMapping("/platform/sms-credits/settings")
    public PlatformSmsCreditSettingsDtos.SettingsResponse updateSettings(
            @Valid @RequestBody PlatformSmsCreditSettingsDtos.UpdateSettingsRequest body
    ) {
        requireSuperAdmin();
        return settingsService.updateSettings(body);
    }

    @GetMapping("/platform/sms-credits/tiers")
    public PlatformSmsCreditSettingsDtos.TiersResponse getTiers() {
        requireSuperAdmin();
        return settingsService.getTiers();
    }

    @PutMapping("/platform/sms-credits/tiers/{tierCode}")
    public PlatformSmsCreditSettingsDtos.TiersResponse upsertTier(
            @PathVariable String tierCode,
            @Valid @RequestBody PlatformSmsCreditSettingsDtos.UpdateTierAllowanceRequest body
    ) {
        requireSuperAdmin();
        return settingsService.upsertTier(tierCode, body);
    }

    @GetMapping("/platform/sms-credits/usage")
    public SmsCreditUsageResponse usage() {
        requireSuperAdmin();
        return creditService.usage();
    }

    // ── Per-business drill-down, grant, override ───────────────────────

    @GetMapping("/businesses/{businessId}/sms-credits")
    public SmsCreditAdminDtos.AccountResponse account(@PathVariable String businessId) {
        requireSuperAdmin();
        BusinessSmsCreditAccount account = creditService.accountOrVirtual(businessId);
        Integer tierAllowance = businessRepository.findById(businessId)
                .map(b -> settingsService.resolveAllowance(b.getSubscriptionTier()))
                .orElse(null);
        int allowance = account.getIncludedOverride() != null
                ? account.getIncludedOverride()
                : (tierAllowance != null ? tierAllowance : 0);
        List<SmsCreditLedgerResponse.SmsCreditLedgerRow> ledger =
                creditService.ledger(businessId, 25).stream()
                        .map(this::toLedgerRow)
                        .toList();
        List<SmsCreditPurchaseDtos.SmsCreditPurchaseResponse> purchases =
                purchaseService.recentForBusiness(businessId).stream()
                        .map(p -> new SmsCreditPurchaseDtos.SmsCreditPurchaseResponse(
                                p.getId(),
                                p.getCredits(),
                                p.getAmountKes(),
                                p.getStatus(),
                                p.getPhoneNumber(),
                                null))
                        .toList();
        return new SmsCreditAdminDtos.AccountResponse(
                businessId,
                account.getIncludedUsed(),
                account.getIncludedOverride(),
                allowance,
                Math.max(0, allowance - account.getIncludedUsed()),
                account.getPurchasedBalance(),
                Math.max(0, allowance - account.getIncludedUsed()) + account.getPurchasedBalance(),
                account.getCycleStartedAt() != null ? account.getCycleStartedAt().toString() : null,
                ledger,
                purchases);
    }

    @PostMapping("/businesses/{businessId}/sms-credits/grant")
    public SmsCreditAdminDtos.AccountResponse grant(
            @PathVariable String businessId,
            @Valid @RequestBody SmsCreditAdminDtos.GrantRequest body
    ) {
        SuperAdmin admin = requireSuperAdmin();
        if (body.credits() == null || body.credits() <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "credits must be positive");
        }
        creditService.grant(businessId, body.credits(), body.note(), admin.getId());
        return account(businessId);
    }

    @PatchMapping("/businesses/{businessId}/sms-credits")
    public SmsCreditAdminDtos.AccountResponse updateAccount(
            @PathVariable String businessId,
            @Valid @RequestBody SmsCreditAdminDtos.UpdateAccountRequest body
    ) {
        requireSuperAdmin();
        creditService.updateIncludedOverride(businessId, body.includedOverride());
        return account(businessId);
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

    private SmsCreditLedgerResponse.SmsCreditLedgerRow toLedgerRow(SmsCreditLedgerEntry e) {
        return new SmsCreditLedgerResponse.SmsCreditLedgerRow(
                e.getId(),
                e.getDelta(),
                e.getBalanceAfter(),
                e.getKind(),
                e.getReason(),
                e.getReferenceId(),
                e.getCreatedAt(),
                e.getCreatedByUserId());
    }
}
