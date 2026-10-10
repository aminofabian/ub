package zelisline.ub.crm.api;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.application.CrmQuickReplyService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/** Saved reply snippets for the inbox composer. */
@Validated
@RestController
@RequestMapping("/api/v1/crm/quick-replies")
@RequiredArgsConstructor
public class CrmQuickReplyController {

    private final CrmQuickReplyService quickReplyService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public List<CrmDtos.QuickReplyRow> list(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return quickReplyService.list(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public CrmDtos.QuickReplyRow create(
            @Valid @RequestBody CrmDtos.QuickReplyRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return quickReplyService.create(
                TenantRequestIds.resolveBusinessId(request), body.shortcut(), body.body());
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public void delete(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        quickReplyService.delete(TenantRequestIds.resolveBusinessId(request), id);
    }
}
