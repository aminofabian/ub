package zelisline.ub.support.api;

import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.support.api.dto.CreateSupportConversationRequest;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
import zelisline.ub.support.api.dto.SupportConversationDetailDto;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.application.SupportService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Tenant-facing support chat. The thread is shared by every signed-in user of
 * the business; any authenticated tenant user may read and reply.
 */
@Validated
@RestController
@RequestMapping("/api/v1/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService supportService;

    @GetMapping("/conversation")
    public SupportConversationDetailDto conversation(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        return supportService.tenantDetail(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/conversation")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportConversationDetailDto create(
            @Valid @RequestBody(required = false) CreateSupportConversationRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return supportService.createConversation(businessId, principal.userId(), body);
    }

    @PostMapping("/conversation/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageDto send(
            @Valid @RequestBody SendSupportMessageRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return supportService.sendTenantMessage(businessId, principal.userId(), body.body());
    }

    @PostMapping("/conversation/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        supportService.markTenantRead(TenantRequestIds.resolveBusinessId(request));
    }

    @PostMapping("/conversation/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        supportService.setTenantStatus(
                TenantRequestIds.resolveBusinessId(request), "RESOLVED", principal.userId());
    }

    @PostMapping("/conversation/reopen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reopen(HttpServletRequest request) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        supportService.setTenantStatus(
                TenantRequestIds.resolveBusinessId(request), "OPEN", principal.userId());
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        return Map.of("count", supportService.tenantUnreadCount(TenantRequestIds.resolveBusinessId(request)));
    }
}
