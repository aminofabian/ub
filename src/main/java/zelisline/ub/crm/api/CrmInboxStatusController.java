package zelisline.ub.crm.api;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmInboxStatusResponse;
import zelisline.ub.crm.application.CrmInboxStatusService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Read-only inbox health for the merchant: whether the WhatsApp channel is on and whether this
 * shop has an active number. Lets the inbox empty state explain why no messages are arriving.
 */
@Validated
@RestController
@RequestMapping("/api/v1/crm/inbox")
@RequiredArgsConstructor
public class CrmInboxStatusController {

    private final CrmInboxStatusService inboxStatusService;

    @GetMapping("/status")
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public CrmInboxStatusResponse status(HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return inboxStatusService.status(TenantRequestIds.resolveBusinessId(request));
    }
}
