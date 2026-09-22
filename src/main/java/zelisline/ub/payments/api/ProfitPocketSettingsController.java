package zelisline.ub.payments.api;

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
import zelisline.ub.payments.api.dto.ProfitPocketSettingsRequest;
import zelisline.ub.payments.api.dto.ProfitPocketSettingsResponse;
import zelisline.ub.payments.application.ProfitPocketSettingsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/payments/profit-pocket")
@RequiredArgsConstructor
public class ProfitPocketSettingsController {

    private final ProfitPocketSettingsService profitPocketSettingsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.read')")
    public ProfitPocketSettingsResponse get(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return profitPocketSettingsService.getSettings(TenantRequestIds.resolveBusinessId(request));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'payments.gateways.write')")
    public ProfitPocketSettingsResponse update(
            @Valid @RequestBody ProfitPocketSettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return profitPocketSettingsService.updateSettings(
                TenantRequestIds.resolveBusinessId(request),
                body);
    }
}
