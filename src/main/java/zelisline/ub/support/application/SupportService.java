package zelisline.ub.support.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;

import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.support.api.dto.CreateSupportConversationRequest;
import zelisline.ub.support.api.dto.SupportConversationDetailDto;
import zelisline.ub.support.api.dto.SupportConversationDto;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.domain.SupportConversation;
import zelisline.ub.support.domain.SupportMessage;
import zelisline.ub.support.repository.SupportConversationRepository;
import zelisline.ub.support.repository.SupportMessageRepository;
import zelisline.ub.tenancy.domain.Business;
import zelisline.ub.tenancy.repository.BusinessRepository;

/**
 * Support chat between a tenant (any signed-in user of the business) and the
 * platform team (super-admin). One conversation per business.
 */
@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);

    private final SupportConversationRepository conversationRepository;
    private final SupportMessageRepository messageRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;

    public SupportService(
            SupportConversationRepository conversationRepository,
            SupportMessageRepository messageRepository,
            BusinessRepository businessRepository,
            UserRepository userRepository,
            ApplicationEventPublisher eventPublisher
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.eventPublisher = eventPublisher;
    }

    // ── Tenant side ─────────────────────────────────────────────────────────

    public Optional<SupportConversation> findByBusinessId(String businessId) {
        return conversationRepository.findByBusinessId(businessId);
    }

    public SupportConversationDetailDto tenantDetail(String businessId) {
        return findByBusinessId(businessId)
                .map(c -> toDetail(c, businessName(businessId)))
                .orElseGet(() -> new SupportConversationDetailDto(null, List.of()));
    }

    public SupportConversationDetailDto createConversation(
            String businessId, String createdByUserId,
            CreateSupportConversationRequest request
    ) {
        SupportConversation conversation = ensureConversation(businessId, createdByUserId,
                resolveUserName(businessId, createdByUserId),
                request == null ? null : request.subject());
        return toDetail(conversation, businessName(businessId));
    }

    @Transactional
    public SupportMessageDto sendTenantMessage(
            String businessId, String userId, String body
    ) {
        String userName = resolveUserName(businessId, userId);
        SupportConversation conversation = ensureConversation(businessId, userId, userName, null);
        // A new message from the tenant reopens a resolved thread.
        if (!SupportConversation.STATUS_OPEN.equals(conversation.getStatus())) {
            conversation.setStatus(SupportConversation.STATUS_OPEN);
            conversationRepository.save(conversation);
            eventPublisher.publishEvent(new SupportEvents.SupportConversationStateEvent(
                    businessId, conversation.getId(), SupportConversation.STATUS_OPEN));
        }

        SupportMessage message = new SupportMessage();
        message.setConversationId(conversation.getId());
        message.setSenderType(SupportMessage.SENDER_TENANT);
        message.setSenderUserId(userId);
        message.setSenderName(userName);
        message.setBody(body);
        messageRepository.save(message);

        conversation.touchLastMessage(message.getCreatedAt(), previewOf(body));
        conversation.setTenantLastReadAt(message.getCreatedAt());
        conversationRepository.save(conversation);

        eventPublisher.publishEvent(new SupportEvents.SupportMessageSentEvent(
                businessId, conversation.getId(), message.getId(), message.getSenderType(),
                message.getSenderUserId(), message.getSenderName(), message.getBody(),
                message.getCreatedAt()));

        return toMessageDto(message);
    }

    /** Marks the tenant's side read and broadcasts read receipts to the platform. */
    @Transactional
    public boolean markTenantRead(String businessId) {
        return findByBusinessId(businessId).map(conversation -> {
            Instant now = Instant.now();
            conversation.setTenantLastReadAt(now);
            conversationRepository.save(conversation);
            int marked = messageRepository.markRead(conversation.getId(), SupportMessage.SENDER_SUPER_ADMIN, now);
            if (marked > 0) {
                eventPublisher.publishEvent(new SupportEvents.SupportMessagesReadEvent(
                        businessId, conversation.getId(), SupportMessage.SENDER_TENANT));
            }
            return marked > 0;
        }).orElse(false);
    }

    @Transactional
    public boolean setTenantStatus(String businessId, String status, String userId) {
        SupportConversation conversation = findByBusinessId(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No support conversation yet"));
        return applyStatus(conversation, status, userId);
    }

    public long tenantUnreadCount(String businessId) {
        return findByBusinessId(businessId)
                .map(c -> messageRepository.countByConversationIdAndSenderTypeAndCreatedAtAfter(
                        c.getId(), SupportMessage.SENDER_SUPER_ADMIN,
                        c.getTenantLastReadAt() != null ? c.getTenantLastReadAt() : Instant.EPOCH))
                .orElse(0L);
    }

    // ── Super-admin side ────────────────────────────────────────────────────

    public List<SupportConversationDto> listForAdmin(String status) {
        List<SupportConversation> rows;
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            rows = conversationRepository.findAllByOrderByLastMessageAtDesc();
        } else {
            rows = conversationRepository.findByStatusOrderByLastMessageAtDesc(status.toUpperCase());
        }
        return rows.stream().map(this::toAdminDto).toList();
    }

    public SupportConversationDetailDto adminDetail(String conversationId) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        return toDetail(conversation, businessName(conversation.getBusinessId()));
    }

    @Transactional
    public SupportMessageDto sendAdminMessage(
            String conversationId, String adminUserId, String adminName, String body
    ) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));

        SupportMessage message = new SupportMessage();
        message.setConversationId(conversation.getId());
        message.setSenderType(SupportMessage.SENDER_SUPER_ADMIN);
        message.setSenderUserId(adminUserId);
        message.setSenderName(adminName);
        message.setBody(body);
        messageRepository.save(message);

        conversation.touchLastMessage(message.getCreatedAt(), previewOf(body));
        conversation.setAdminLastReadAt(message.getCreatedAt());
        conversationRepository.save(conversation);

        eventPublisher.publishEvent(new SupportEvents.SupportMessageSentEvent(
                conversation.getBusinessId(), conversation.getId(), message.getId(), message.getSenderType(),
                message.getSenderUserId(), message.getSenderName(), message.getBody(),
                message.getCreatedAt()));

        return toMessageDto(message);
    }

    /** Marks the platform's side read and broadcasts read receipts to the tenant. */
    @Transactional
    public boolean markAdminRead(String conversationId) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        Instant now = Instant.now();
        conversation.setAdminLastReadAt(now);
        conversationRepository.save(conversation);
        int marked = messageRepository.markRead(conversation.getId(), SupportMessage.SENDER_TENANT, now);
        if (marked > 0) {
            eventPublisher.publishEvent(new SupportEvents.SupportMessagesReadEvent(
                    conversation.getBusinessId(), conversation.getId(), SupportMessage.SENDER_SUPER_ADMIN));
        }
        return marked > 0;
    }

    @Transactional
    public boolean setAdminStatus(String conversationId, String status) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        return applyStatus(conversation, status, null);
    }

    public long adminUnreadCount() {
        long total = 0;
        for (SupportConversation conversation : conversationRepository.findAll()) {
            total += messageRepository.countByConversationIdAndSenderTypeAndCreatedAtAfter(
                    conversation.getId(), SupportMessage.SENDER_TENANT,
                    conversation.getAdminLastReadAt() != null
                            ? conversation.getAdminLastReadAt()
                            : Instant.EPOCH);
        }
        return total;
    }

    public SupportConversation getConversationForTyping(String conversationId) {
        return conversationRepository.findById(conversationId).orElse(null);
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private boolean applyStatus(SupportConversation conversation, String status, String actorUserId) {
        if (!SupportConversation.STATUS_OPEN.equals(status)
                && !SupportConversation.STATUS_RESOLVED.equals(status)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown conversation status: " + status);
        }
        if (conversation.getStatus().equals(status)) {
            return false;
        }
        conversation.setStatus(status);
        conversationRepository.save(conversation);
        eventPublisher.publishEvent(new SupportEvents.SupportConversationStateEvent(
                conversation.getBusinessId(), conversation.getId(), status));
        log.debug("Support conversation {} → {} by {}", conversation.getId(), status, actorUserId);
        return true;
    }

    private SupportConversation ensureConversation(
            String businessId, String createdByUserId, String createdByName, String subject
    ) {
        return conversationRepository.findByBusinessId(businessId).orElseGet(() -> {
            SupportConversation conversation = new SupportConversation();
            conversation.setBusinessId(businessId);
            conversation.setStatus(SupportConversation.STATUS_OPEN);
            conversation.setCreatedBy(createdByUserId);
            conversation.setCreatedByName(createdByName);
            String cleaned = subject == null ? null : subject.trim();
            if (cleaned != null && !cleaned.isBlank() && cleaned.length() > 191) {
                cleaned = cleaned.substring(0, 191);
            }
            conversation.setSubject(cleaned);
            try {
                conversationRepository.saveAndFlush(conversation);
                return conversation;
            } catch (DataIntegrityViolationException ex) {
                // Concurrent create from two devices — the unique business_id wins.
                return conversationRepository.findByBusinessId(businessId)
                        .orElseThrow(() -> ex);
            }
        });
    }

    private SupportConversationDetailDto toDetail(SupportConversation conversation, String businessName) {
        List<SupportMessageDto> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .map(SupportService::toMessageDto)
                .toList();
        return new SupportConversationDetailDto(
                toAdminDto(conversation, businessName, tenantUnreadCount(conversation.getBusinessId())),
                messages);
    }

    private SupportConversationDto toAdminDto(SupportConversation conversation) {
        return toAdminDto(conversation, businessName(conversation.getBusinessId()),
                unreadFor(conversation, SupportMessage.SENDER_TENANT));
    }

    private SupportConversationDto toAdminDto(
            SupportConversation conversation, String businessName, long unreadCount
    ) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(conversation.getBusinessId()).orElse(null);
        String slug = business != null ? business.getSlug() : null;
        return new SupportConversationDto(
                conversation.getId(),
                conversation.getBusinessId(),
                businessName,
                slug,
                conversation.getStatus(),
                conversation.getSubject(),
                conversation.getCreatedByName(),
                conversation.getLastMessageAt(),
                conversation.getLastMessagePreview(),
                conversation.getTenantLastReadAt(),
                conversation.getAdminLastReadAt(),
                unreadCount,
                conversation.getCreatedAt(),
                conversation.getUpdatedAt()
        );
    }

    private long unreadFor(SupportConversation conversation, String senderType) {
        Instant after = SupportMessage.SENDER_TENANT.equals(senderType)
                ? (conversation.getAdminLastReadAt() != null ? conversation.getAdminLastReadAt() : Instant.EPOCH)
                : (conversation.getTenantLastReadAt() != null ? conversation.getTenantLastReadAt() : Instant.EPOCH);
        return messageRepository.countByConversationIdAndSenderTypeAndCreatedAtAfter(
                conversation.getId(), senderType, after);
    }

    private String businessName(String businessId) {
        return businessRepository.findByIdAndDeletedAtIsNull(businessId)
                .map(Business::getName)
                .orElse(null);
    }

    private String resolveUserName(String businessId, String userId) {
        try {
            return userRepository.findByIdAndBusinessIdAndDeletedAtIsNull(userId, businessId)
                    .map(zelisline.ub.identity.domain.User::getName)
                    .orElse(null);
        } catch (Exception ex) {
            log.debug("Could not resolve user name for {} in {}: {}", userId, businessId, ex.getMessage());
            return null;
        }
    }

    private static SupportMessageDto toMessageDto(SupportMessage message) {
        return new SupportMessageDto(
                message.getId(),
                message.getConversationId(),
                message.getSenderType(),
                message.getSenderUserId(),
                message.getSenderName(),
                message.getBody(),
                message.getReadAt(),
                message.getCreatedAt()
        );
    }

    private static String previewOf(String body) {
        if (body == null) {
            return null;
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 140 ? flat.substring(0, 140) + "…" : flat;
    }
}
