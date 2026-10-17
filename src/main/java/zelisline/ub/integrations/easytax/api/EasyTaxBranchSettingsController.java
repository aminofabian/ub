package zelisline.ub.integrations.easytax.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxBranchSettingsRequest;
import zelisline.ub.integrations.easytax.api.dto.EasyTaxBranchSettingsResponse;
import zelisline.ub.integrations.easytax.application.EasyTaxBranchSettingsService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** Per-branch KRA identity for the current business. */
@Validated
@RestController
@RequestMapping("/api/v1/integrations/easytax/branches/{branchId}")
@RequiredArgsConstructor
public class EasyTaxBranchSettingsController {

    private final EasyTaxBranchSettingsService service;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'integrations.easytax.read')")
    public EasyTaxBranchSettingsResponse get(
            @PathVariable String branchId,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return service.get(TenantRequestIds.resolveBusinessId(request), branchId);
    }

    @PutMapping
    @PreAuthorize("hasPermission(null, 'integrations.easytax.write')")
    public EasyTaxBranchSettingsResponse update(
            @PathVariable String branchId,
            @Valid @RequestBody EasyTaxBranchSettingsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return service.update(
                TenantRequestIds.resolveBusinessId(request), branchId, body);
    }
}
