package zelisline.ub.payments.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.payments.api.dto.AdjustKioskPayAccountRequest;
import zelisline.ub.payments.api.dto.KioskPayAccountResponse;
import zelisline.ub.payments.api.dto.KioskPayAccountSummary;
import zelisline.ub.payments.api.dto.PlatformKioskPaySettingsResponse;
import zelisline.ub.payments.api.dto.UpdatePlatformKioskPaySettingsRequest;
import zelisline.ub.payments.application.KioskPayWalletService;
import zelisline.ub.payments.application.PlatformKioskPaySettingsService;

@RestController
@RequestMapping("/api/v1/super-admin/payments/kiosk-pay")
@RequiredArgsConstructor
public class SuperAdminKioskPayController {

    private final PlatformKioskPaySettingsService settingsService;
    private final KioskPayWalletService walletService;

    @GetMapping
    public PlatformKioskPaySettingsResponse get() {
        return settingsService.getForSuperAdmin();
    }

    @PatchMapping
    public PlatformKioskPaySettingsResponse update(@Valid @RequestBody UpdatePlatformKioskPaySettingsRequest body) {
        return settingsService.update(body);
    }

    /** Tenant wallet rows so platform ops can reconcile the custody float. */
    @GetMapping("/accounts")
    public List<KioskPayAccountResponse> accounts(
            @RequestParam(defaultValue = "50") int limit
    ) {
        return walletService.listAccountsForSuperAdmin(limit);
    }

    /** Platform-wide float totals. */
    @GetMapping("/accounts/summary")
    public KioskPayAccountSummary summary() {
        return walletService.accountSummaryForSuperAdmin();
    }

    /** Manual wallet adjustment (refund reversal / correction). Delta is signed. */
    @PostMapping("/accounts/{businessId}/adjust")
    public KioskPayAccountResponse adjust(
            @PathVariable String businessId,
            @Valid @RequestBody AdjustKioskPayAccountRequest body
    ) {
        if (body.note() == null || body.note().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "note is required for adjustments");
        }
        return walletService.adjustBalance(businessId, body.delta(), body.note());
    }
}
