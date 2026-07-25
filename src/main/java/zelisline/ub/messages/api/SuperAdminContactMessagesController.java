package zelisline.ub.messages.api;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import zelisline.ub.messages.api.dto.ContactMessageDetailResponse;
import zelisline.ub.messages.api.dto.ContactMessageListItemResponse;
import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.api.dto.ContactMessageReplyResponse;
import zelisline.ub.messages.application.ContactMessageService;
import zelisline.ub.messages.domain.ContactMessageStatus;

@Validated
@RestController
@RequestMapping("/api/v1/super-admin/contact-messages")
@RequiredArgsConstructor
public class SuperAdminContactMessagesController {

    private final ContactMessageService contactMessageService;

    @GetMapping
    public Page<ContactMessageListItemResponse> list(
            Pageable pageable,
            @RequestParam(required = false) ContactMessageStatus status
    ) {
        return contactMessageService.listPlatform(status, pageable);
    }

    @GetMapping("/{id}")
    public ContactMessageDetailResponse get(@PathVariable String id) {
        return contactMessageService.getPlatformAndMarkRead(id);
    }

    @PatchMapping("/{id}/read")
    public ContactMessageDetailResponse markRead(@PathVariable String id) {
        return contactMessageService.markPlatformRead(id);
    }

    @PostMapping("/{id}/replies")
    public ContactMessageReplyResponse reply(
            @PathVariable String id,
            @Valid @RequestBody ContactMessageReplyRequest body
    ) {
        return contactMessageService.replyPlatform(id, body, currentSuperAdminId());
    }

    private static String currentSuperAdminId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || authentication.getPrincipal() == null) {
            return null;
        }
        return String.valueOf(authentication.getPrincipal());
    }
}
