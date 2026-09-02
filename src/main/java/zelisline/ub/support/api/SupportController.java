package zelisline.ub.support.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.platform.security.TenantPrincipal;
import zelisline.ub.serving.api.dto.ServingDtos;
import zelisline.ub.serving.application.ServingTicketService;
import zelisline.ub.support.api.dto.CreateSupportConversationRequest;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
import zelisline.ub.support.api.dto.SupportConversationDetailDto;
import zelisline.ub.support.api.dto.SupportConversationDto;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.application.SupportService;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Tenant-facing support chat. Two surfaces:
 * <ul>
 *   <li>the business's own thread with the platform team (classic), and</li>
 *   <li>storefront buyer threads — anonymous shoppers who started a chat on the
 *       public storefront and are answered here by the tenant's staff.</li>
 * </ul>
 */
@Validated
@RestController
@RequestMapping("/api/v1/support")
@RequiredArgsConstructor
public class SupportController {

    private final SupportService supportService;
    private final ServingTicketService servingTicketService;

    // ── Platform thread (the classic tenant → super-admin chat) ─────────────

    @GetMapping("/conversation")
    public SupportConversationDetailDto conversation(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
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
        return supportService.sendTenantMessage(businessId, principal.userId(), body);
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

    // ── Storefront buyer threads (staff answers shoppers here) ─────────────

    @GetMapping("/storefront/conversations")
    public Map<String, Object> storefrontConversations(
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        List<SupportConversationDto> conversations = supportService.listStorefrontForTenant(businessId, status);
        return Map.of(
                "conversations", conversations,
                "total", conversations.size(),
                "unread", supportService.storefrontStaffUnreadCount(businessId));
    }

    @GetMapping("/storefront/conversations/{id}")
    public SupportConversationDetailDto storefrontConversation(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        SupportConversationDetailDto detail = supportService.storefrontDetail(id, businessId);
        supportService.markStorefrontStaffRead(id, businessId);
        return detail;
    }

    @PostMapping("/storefront/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageDto storefrontSend(
            @PathVariable String id,
            @Valid @RequestBody SendSupportMessageRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return supportService.sendStorefrontStaffMessage(id, businessId, principal.userId(), body);
    }

    @PostMapping("/storefront/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void storefrontMarkRead(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        supportService.markStorefrontStaffRead(id, businessId);
    }

    @GetMapping("/storefront/unread-count")
    public Map<String, Object> storefrontUnreadCount(HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return Map.of("count", supportService.storefrontStaffUnreadCount(businessId));
    }

    @PostMapping("/storefront/conversations/{id}/escalate")
    public ServingDtos.TicketSummary escalateStorefront(
            @PathVariable String id,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return servingTicketService.escalateStorefront(
                businessId, id, principal.userId(), "Shop staff");
    }

    @GetMapping("/tickets")
    public Map<String, Object> tickets(
            @RequestParam(required = false) String status,
            HttpServletRequest request
    ) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        List<ServingDtos.TicketSummary> tickets = servingTicketService.listForTenant(businessId, status);
        return Map.of("tickets", tickets, "total", tickets.size());
    }

    @PostMapping("/tickets")
    @ResponseStatus(HttpStatus.CREATED)
    public ServingDtos.TicketSummary createTicket(
            @RequestBody ServingDtos.TenantCreateTicketRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return servingTicketService.createForTenant(businessId, principal.userId(), "Shop staff", body);
    }

    @GetMapping("/tickets/{id}")
    public ServingDtos.TicketDetail ticket(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return servingTicketService.getForTenant(businessId, id);
    }

    @PostMapping("/tickets/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageDto ticketReply(
            @PathVariable String id,
            @Valid @RequestBody SendSupportMessageRequest body,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return servingTicketService.replyAsTenant(
                businessId,
                id,
                principal.userId(),
                "Shop staff",
                body == null ? null : body.body());
    }

    @PostMapping("/tickets/{id}/points/{pointId}/complete")
    public ServingDtos.TicketPoint completePoint(
            @PathVariable String id,
            @PathVariable String pointId,
            HttpServletRequest request
    ) {
        TenantPrincipal principal = CurrentTenantUser.requireHuman(request);
        String businessId = TenantRequestIds.resolveBusinessId(request);
        return servingTicketService.completePointAsTenant(
                businessId,
                id,
                pointId,
                principal.userId(),
                "Shop staff");
    }
}
