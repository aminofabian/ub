package zelisline.ub.payroll.api;

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
import zelisline.ub.payroll.api.dto.PayrollAutomationSettingsRequest;
import zelisline.ub.payroll.api.dto.PayrollAutomationSettingsResponse;
import zelisline.ub.payroll.application.PayrollAutomationSettingsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/payroll")
@RequiredArgsConstructor
public class PayrollAutomationController {

    private final PayrollAutomationSettingsService automationSettingsService;

    @GetMapping("/automation")
    @PreAuthorize("hasPermission(null, 'payroll.view')")
    public PayrollAutomationSettingsResponse getAutomation(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return automationSettingsService.getSettings(TenantRequestIds.resolveBusinessId(request));
    }

    @PutMapping("/automation")
    @PreAuthorize("hasPermission(null, 'payroll.manage')")
    public PayrollAutomationSettingsResponse updateAutomation(
            @Valid @RequestBody PayrollAutomationSettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        return automationSettingsService.updateSettings(
                TenantRequestIds.resolveBusinessId(request),
                body
        );
    }
}
