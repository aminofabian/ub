package zelisline.ub.credits.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.credits.api.dto.LoyaltySettingsResponse;
import zelisline.ub.credits.api.dto.UpdateLoyaltySettingsRequest;
import zelisline.ub.credits.application.BusinessCreditSettingsService;
import zelisline.ub.credits.domain.BusinessCreditSettings;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/credits/loyalty-settings")
@RequiredArgsConstructor
public class LoyaltySettingsController {

    private final BusinessCreditSettingsService businessCreditSettingsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'credits.customers.read')")
    public LoyaltySettingsResponse get(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        BusinessCreditSettings s = businessCreditSettingsService
                .resolveForBusiness(TenantRequestIds.resolveBusinessId(request));
        return toResponse(s);
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'credits.settings.write')")
    public LoyaltySettingsResponse put(
            @Valid @RequestBody UpdateLoyaltySettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        BusinessCreditSettings s = businessCreditSettingsService.updateLoyaltyTunables(
                TenantRequestIds.resolveBusinessId(request),
                body.loyaltyPointsPerKes(),
                body.loyaltyKesPerPoint(),
                body.loyaltyMaxRedeemBps());
        return toResponse(s);
    }

    private static LoyaltySettingsResponse toResponse(BusinessCreditSettings s) {
        return new LoyaltySettingsResponse(
                s.getLoyaltyPointsPerKes(),
                s.getLoyaltyKesPerPoint(),
                s.getLoyaltyMaxRedeemBps());
    }
}
