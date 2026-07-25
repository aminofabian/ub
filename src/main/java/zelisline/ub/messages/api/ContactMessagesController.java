package zelisline.ub.messages.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.messages.api.dto.ContactMessageDetailResponse;
import zelisline.ub.messages.api.dto.ContactMessageListItemResponse;
import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.api.dto.ContactMessageReplyResponse;
import zelisline.ub.messages.application.ContactMessageService;
import zelisline.ub.messages.domain.ContactMessageStatus;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

@Validated
@RestController
@RequestMapping("/api/v1/contact-messages")
@RequiredArgsConstructor
public class ContactMessagesController {

    private final ContactMessageService contactMessageService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'messages.read')")
    public Page<ContactMessageListItemResponse> list(
            Pageable pageable,
            @RequestParam(required = false) ContactMessageStatus status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return contactMessageService.listTenant(businessId, status, pageable);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'messages.read')")
    public ContactMessageDetailResponse get(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return contactMessageService.getTenantAndMarkRead(businessId, id);
    }

    @PatchMapping("/{id}/read")
    @PreAuthorize("hasPermission(null, 'messages.read')")
    public ContactMessageDetailResponse markRead(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return contactMessageService.markTenantRead(businessId, id);
    }

    @PostMapping("/{id}/replies")
    @PreAuthorize("hasPermission(null, 'messages.reply')")
    public ContactMessageReplyResponse reply(
            @PathVariable String id,
            @Valid @RequestBody ContactMessageReplyRequest body,
            HttpServletRequest request
    ) {
        var principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return contactMessageService.replyTenant(businessId, id, body, principal.userId());
    }
}
