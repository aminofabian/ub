package zelisline.ub.integrations.easytax.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxSettingsRequest;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxSettingsResponse;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxTestResponse;
import zelisline.ub.integrations.easytax.application.EasyTaxSettingsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Opt-in EasyTax (KRA eTIMS) integration settings for the current business.
 * The shop chooses whether to enable it and enters its device details here.
 */
@Validated
@RestController
@RequestMapping("/api/v1/integrations/easytax")
@RequiredArgsConstructor
public class EasyTaxSettingsController {

    private final EasyTaxSettingsService easyTaxSettingsService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'integrations.easytax.read')")
    public EasyTaxSettingsResponse get(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return easyTaxSettingsService.getSettings(TenantRequestIds.resolveBusinessId(request));
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'integrations.easytax.write')")
    public EasyTaxSettingsResponse update(
            @Valid @RequestBody EasyTaxSettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return easyTaxSettingsService.updateSettings(
                TenantRequestIds.resolveBusinessId(request),
                body);
    }

    @PostMapping("/test")
    @PreAuthorize("hasPermission(null, 'integrations.easytax.write')")
    public EasyTaxTestResponse test(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return easyTaxSettingsService.testConnection(TenantRequestIds.resolveBusinessId(request));
    }
}
