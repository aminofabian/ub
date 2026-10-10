package zelisline.ub.crm.api;

import java.util.List;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

import zelisline.ub.crm.api.dto.CrmDtos;
import zelisline.ub.crm.application.CrmConversationService;
import zelisline.ub.platform.security.CurrentTenantUser;
import zelisline.ub.tenancy.api.TenantRequestIds;

/**
 * Merchant WhatsApp inbox: conversations, replies, assignment, notes, tags, payment prompts,
 * AI drafts.
 *
 * <p>Tenant-scoped via the request tenant; authorized by {@code crm.inbox.*} permissions.
 * See {@code docs/scopes/whatsapp-crm/SCOPE.md} §8.
 */
@Validated
@RestController
@RequestMapping("/api/v1/crm/conversations")
@RequiredArgsConstructor
public class CrmConversationController {

    private final CrmConversationService conversationService;

    @GetMapping
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public CrmDtos.ConversationsPage list(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "assignee", required = false) String assignee,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "30") int size,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return conversationService.list(
                TenantRequestIds.resolveBusinessId(request), status, assignee, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public CrmDtos.ConversationDetail get(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return conversationService.get(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/messages")
    @PreAuthorize("hasPermission(null, 'crm.inbox.send')")
    public CrmDtos.SendMessageResponse send(
            @PathVariable String id,
            @Valid @RequestBody CrmDtos.SendMessageRequest body,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return conversationService.send(
                TenantRequestIds.resolveBusinessId(request), id, body.body(), idempotencyKey);
    }

    /** M5: draft a reply with SokoMind for the agent to review. */
    @PostMapping("/{id}/ai-draft")
    @PreAuthorize("hasPermission(null, 'crm.inbox.send')")
    public CrmDtos.AiDraftResponse aiDraft(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return conversationService.draftAiReply(TenantRequestIds.resolveBusinessId(request), id);
    }

    @PostMapping("/{id}/assign")
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public CrmDtos.ConversationRow assign(
            @PathVariable String id,
            @Valid @RequestBody CrmDtos.AssignRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return conversationService.assign(TenantRequestIds.resolveBusinessId(request), id, body.userId());
    }

    @PostMapping("/{id}/tags")
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public CrmDtos.ConversationRow setTags(
            @PathVariable String id,
            @Valid @RequestBody CrmDtos.SetContactTagsRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return conversationService.setContactTags(
                TenantRequestIds.resolveBusinessId(request), id, body.tags());
    }

    /** M5: send an M-Pesa STK prompt to the customer (money handled in `payments`). */
    @PostMapping("/{id}/payment-prompt")
    @PreAuthorize("hasPermission(null, 'crm.inbox.send')")
    public CrmDtos.PaymentPromptResponse requestPayment(
            @PathVariable String id,
            @Valid @RequestBody CrmDtos.RequestPaymentRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return conversationService.requestPayment(
                TenantRequestIds.resolveBusinessId(request), id, body.amount());
    }

    @PostMapping("/{id}/notes")
    @PreAuthorize("hasPermission(null, 'crm.inbox.manage')")
    public CrmDtos.NoteRow addNote(
            @PathVariable String id,
            @Valid @RequestBody CrmDtos.NoteRequest body,
            HttpServletRequest request
    ) {
        CurrentTenantUser.require(request);
        return conversationService.addNote(
                TenantRequestIds.resolveBusinessId(request),
                id,
                body.body(),
                CurrentTenantUser.auditActorId(request));
    }

    @GetMapping("/{id}/notes")
    @PreAuthorize("hasPermission(null, 'crm.inbox.read')")
    public List<CrmDtos.NoteRow> listNotes(@PathVariable String id, HttpServletRequest request) {
        CurrentTenantUser.require(request);
        return conversationService.listNotes(TenantRequestIds.resolveBusinessId(request), id);
    }
}
