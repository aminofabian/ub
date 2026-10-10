package zelisline.ub.crm.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.application.CrmAutomationService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** No-code automation rules for the WhatsApp inbox (M3). */
@Validated
@RestController
@RequestMapping("/api/v1/crm/automations")
@RequiredArgsConstructor
public class CrmAutomationController {

    private final CrmAutomationService automationService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public List<CrmDtos.AutomationRow> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return automationService.list(TenantRequestIds.resolveBusinessId(request));
    }

    @GetMapping("/runs")
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public CrmDtos.AutomationRunsPage listRuns(
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "30") int size,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return automationService.listRuns(TenantRequestIds.resolveBusinessId(request), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public CrmDtos.AutomationDetail get(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return automationService.get(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasPermission(null, 'crm.automation.manage')")
    public CrmDtos.AutomationDetail create(
            @Valid @RequestBody CrmDtos.UpsertAutomationRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return automationService.create(TenantRequestIds.resolveBusinessId(request), body);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'crm.automation.manage')")
    public CrmDtos.AutomationDetail update(
            @PathVariable String id,
            @Valid @RequestBody CrmDtos.UpsertAutomationRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return automationService.update(TenantRequestIds.resolveBusinessId(request), id, body);
    }

    @PostMapping("/{id}/active")
    @PreAuthorize("hasPermission(null, 'crm.automation.manage')")
    public CrmDtos.AutomationRow setActive(
            @PathVariable String id,
            @RequestBody CrmDtos.SetActiveRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return automationService.setActive(TenantRequestIds.resolveBusinessId(request), id, body.active());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'crm.automation.manage')")
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        automationService.delete(TenantRequestIds.resolveBusinessId(request), id);
    }
}
