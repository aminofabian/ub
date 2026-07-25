package zelisline.ub.messages.application;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import zelisline.ub.messages.api.dto.ContactMessageDetailResponse;
import zelisline.ub.messages.api.dto.ContactMessageListItemResponse;
import zelisline.ub.messages.api.dto.ContactMessageReplyRequest;
import zelisline.ub.messages.api.dto.ContactMessageReplyResponse;
import zelisline.ub.messages.api.dto.PublicContactMessageRequest;
import zelisline.ub.messages.api.dto.PublicContactMessageResponse;
import zelisline.ub.messages.domain.ContactMessage;
import zelisline.ub.messages.domain.ContactMessageReply;
import zelisline.ub.messages.domain.ContactMessageScope;
import zelisline.ub.messages.domain.ContactMessageStatus;
import zelisline.ub.messages.repository.ContactMessageReplyRepository;
import zelisline.ub.messages.repository.ContactMessageRepository;
import zelisline.ub.payments.application.StkPhoneNormalizer;
import zelisline.ub.storefront.application.PublicStorefrontContextService;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

@Service
@RequiredArgsConstructor
public class ContactMessageService {

    private static final int PREVIEW_LEN = 140;

    private final ContactMessageRepository contactMessageRepository;
    private final ContactMessageReplyRepository contactMessageReplyRepository;
    private final PublicStorefrontContextService storefrontContextService;
    private final BusinessRepository businessRepository;
    private final ContactMessageReplySender contactMessageReplySender;

    @Transactional
    public PublicContactMessageResponse submitPlatform(
            PublicContactMessageRequest body, HttpServletRequest request) {
        ContactMessage saved = persist(ContactMessageScope.PLATFORM, null, body, request);
        return new PublicContactMessageResponse(true, saved.getId());
    }

    @Transactional
    public PublicContactMessageResponse submitTenant(
            String slug, PublicContactMessageRequest body, HttpServletRequest request) {
        var ctx = storefrontContextService.requireForSlug(slug);
        ContactMessage saved =
                persist(ContactMessageScope.TENANT, ctx.business().getId(), body, request);
        return new PublicContactMessageResponse(true, saved.getId());
    }

    @Transactional(readOnly = true)
    public Page<ContactMessageListItemResponse> listTenant(
            String businessId, ContactMessageStatus status, Pageable pageable) {
        Page<ContactMessage> page = status == null
                ? contactMessageRepository.findByScopeAndBusinessIdOrderByCreatedAtDesc(
                        ContactMessageScope.TENANT, businessId, pageable)
                : contactMessageRepository.findByScopeAndBusinessIdAndStatusOrderByCreatedAtDesc(
                        ContactMessageScope.TENANT, businessId, status, pageable);
        return page.map(this::toListItem);
    }

    @Transactional(readOnly = true)
    public Page<ContactMessageListItemResponse> listPlatform(
            ContactMessageStatus status, Pageable pageable) {
        Page<ContactMessage> page = status == null
                ? contactMessageRepository.findByScopeOrderByCreatedAtDesc(
                        ContactMessageScope.PLATFORM, pageable)
                : contactMessageRepository.findByScopeAndStatusOrderByCreatedAtDesc(
                        ContactMessageScope.PLATFORM, status, pageable);
        return page.map(this::toListItem);
    }

    @Transactional
    public ContactMessageDetailResponse getTenantAndMarkRead(String businessId, String id) {
        ContactMessage message = requireTenantMessage(businessId, id);
        markReadIfNeeded(message);
        return toDetail(message);
    }

    @Transactional
    public ContactMessageDetailResponse getPlatformAndMarkRead(String id) {
        ContactMessage message = requirePlatformMessage(id);
        markReadIfNeeded(message);
        return toDetail(message);
    }

    @Transactional
    public ContactMessageDetailResponse markTenantRead(String businessId, String id) {
        ContactMessage message = requireTenantMessage(businessId, id);
        markReadIfNeeded(message);
        return toDetail(message);
    }

    @Transactional
    public ContactMessageDetailResponse markPlatformRead(String id) {
        ContactMessage message = requirePlatformMessage(id);
        markReadIfNeeded(message);
        return toDetail(message);
    }

    @Transactional
    public ContactMessageReplyResponse replyTenant(
            String businessId, String id, ContactMessageReplyRequest body, String actorUserId) {
        ContactMessage message = requireTenantMessage(businessId, id);
        Business business = businessRepository
                .findByIdAndDeletedAtIsNull(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        ContactMessageReply reply = contactMessageReplySender.send(
                message, body, actorUserId, business.getName(), false);
        markReadIfNeeded(message);
        return toReply(reply);
    }

    @Transactional
    public ContactMessageReplyResponse replyPlatform(
            String id, ContactMessageReplyRequest body, String actorUserId) {
        ContactMessage message = requirePlatformMessage(id);
        ContactMessageReply reply =
                contactMessageReplySender.send(message, body, actorUserId, "Kiosk", true);
        markReadIfNeeded(message);
        return toReply(reply);
    }

    private ContactMessage persist(
            ContactMessageScope scope,
            String businessId,
            PublicContactMessageRequest body,
            HttpServletRequest request
    ) {
        rejectSpam(body);
        ContactMessage message = new ContactMessage();
        message.setScope(scope);
        message.setBusinessId(businessId);
        message.setName(body.name().trim());
        message.setEmail(body.email().trim().toLowerCase(Locale.ROOT));
        message.setPhone(normalizeOptionalPhone(body.phone()));
        message.setBody(body.message().trim());
        message.setStatus(ContactMessageStatus.UNREAD);
        if (body.sourcePath() != null && !body.sourcePath().isBlank()) {
            message.setSourcePath(body.sourcePath().trim());
        }
        String ua = request.getHeader("User-Agent");
        if (ua != null && !ua.isBlank()) {
            message.setUserAgent(ua.length() > 512 ? ua.substring(0, 512) : ua);
        }
        return contactMessageRepository.save(message);
    }

    static void rejectSpam(PublicContactMessageRequest body) {
        ContactTillChallengeVerifier.verify(body);
    }

    private ContactMessage requireTenantMessage(String businessId, String id) {
        return contactMessageRepository
                .findByIdAndScopeAndBusinessId(id, ContactMessageScope.TENANT, businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
    }

    private ContactMessage requirePlatformMessage(String id) {
        return contactMessageRepository
                .findByIdAndScope(id, ContactMessageScope.PLATFORM)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Message not found"));
    }

    private void markReadIfNeeded(ContactMessage message) {
        if (message.getStatus() == ContactMessageStatus.READ) {
            return;
        }
        message.setStatus(ContactMessageStatus.READ);
        message.setReadAt(Instant.now());
        contactMessageRepository.save(message);
    }

    private ContactMessageListItemResponse toListItem(ContactMessage message) {
        String body = message.getBody() == null ? "" : message.getBody();
        String preview = body.length() <= PREVIEW_LEN ? body : body.substring(0, PREVIEW_LEN) + "…";
        return new ContactMessageListItemResponse(
                message.getId(),
                message.getName(),
                message.getEmail(),
                message.getPhone(),
                preview,
                message.getStatus().name(),
                message.getCreatedAt(),
                message.getReadAt());
    }

    private ContactMessageDetailResponse toDetail(ContactMessage message) {
        List<ContactMessageReplyResponse> replies = contactMessageReplyRepository
                .findByContactMessageIdOrderByCreatedAtAsc(message.getId())
                .stream()
                .map(this::toReply)
                .toList();
        return new ContactMessageDetailResponse(
                message.getId(),
                message.getName(),
                message.getEmail(),
                message.getPhone(),
                message.getBody(),
                message.getStatus().name(),
                message.getCreatedAt(),
                message.getReadAt(),
                message.getSourcePath(),
                replies);
    }

    private ContactMessageReplyResponse toReply(ContactMessageReply reply) {
        return new ContactMessageReplyResponse(
                reply.getId(),
                reply.getChannel().name(),
                reply.getBody(),
                reply.getOutcome(),
                reply.getDetail(),
                reply.getSentByUserId(),
                reply.getCreatedAt());
    }

    private static String normalizeOptionalPhone(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = StkPhoneNormalizer.normalize(raw);
        if (normalized == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Phone number is invalid");
        }
        return normalized;
    }
}
