package zelisline.ub.support.api;

import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.identity.domain.SuperAdmin;
import zelisline.ub.identity.repository.SuperAdminRepository;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
import zelisline.ub.support.api.dto.SupportConversationDetailDto;
import zelisline.ub.support.api.dto.SupportConversationDto;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.application.SupportPresenceService;
import zelisline.ub.support.application.SupportService;

/**
 * Super-admin support inbox: every tenant's support thread, one conversation
 * each. Guarded by {@code ROLE_SUPER_ADMIN} at the route level
 * ({@code /api/v1/super-admin/**}).
 */
@Validated
@RestController
@RequestMapping("/api/v1/super-admin/support")
@RequiredArgsConstructor
public class SuperAdminSupportController {

    private final SupportService supportService;
    private final SuperAdminRepository superAdminRepository;
    private final SupportPresenceService supportPresenceService;

    @GetMapping("/conversations")
    public Map<String, Object> conversations(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String type
    ) {
        List<SupportConversationDto> conversations = supportService.listForAdmin(status, type);
        return Map.of(
                "conversations", conversations,
                "total", conversations.size(),
                "unread", supportService.adminUnreadCount());
    }

    @GetMapping("/conversations/{id}")
    public SupportConversationDetailDto conversation(@PathVariable String id) {
        SupportConversationDetailDto detail = supportService.adminDetail(id);
        supportService.markAdminRead(id);
        return detail;
    }

    @PostMapping("/conversations/{id}/messages")
    @ResponseStatus(HttpStatus.CREATED)
    public SupportMessageDto send(@PathVariable String id, @Valid @RequestBody SendSupportMessageRequest body) {
        SuperAdmin admin = requireSuperAdmin();
        return supportService.sendAdminMessage(id, admin.getId(), admin.getName(), body.body());
    }

    @PostMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void markRead(@PathVariable String id) {
        supportService.markAdminRead(id);
    }

    @PostMapping("/conversations/{id}/resolve")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void resolve(@PathVariable String id) {
        supportService.setAdminStatus(id, "RESOLVED");
    }

    @PostMapping("/conversations/{id}/reopen")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reopen(@PathVariable String id) {
        supportService.setAdminStatus(id, "OPEN");
    }

    @GetMapping("/unread-count")
    public Map<String, Object> unreadCount() {
        return Map.of("count", supportService.adminUnreadCount());
    }

    /** Live presence: tenant threads keyed by businessId, visitor threads keyed by guestId. */
    @GetMapping("/presence")
    public Map<String, Object> presence() {
        List<SupportConversationDto> tenants = supportService.listForAdmin(null, "TENANT");
        List<SupportConversationDto> visitors = supportService.listForAdmin(null, "VISITOR");
        List<String> businessIds = tenants.stream()
                .map(SupportConversationDto::businessId)
                .toList();
        List<String> guestIds = visitors.stream()
                .map(SupportConversationDto::guestId)
                .filter(java.util.Objects::nonNull)
                .toList();
        return Map.of(
                "presence", supportPresenceService.snapshot(businessIds),
                "guestPresence", supportPresenceService.guestSnapshot(guestIds));
    }

    private SuperAdmin requireSuperAdmin() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        String id = (String) authentication.getPrincipal();
        return superAdminRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Super admin not found"));
    }
}
