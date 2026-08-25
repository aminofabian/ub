package zelisline.ub.support.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.support.api.dto.CreateSupportConversationRequest;
import zelisline.ub.support.api.dto.GuestThreadDto;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
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
 * One engine for every support thread:
 *
 * <ul>
 *   <li>{@code TENANT} — the business's own thread with the platform team
 *       (one per business; the classic tenant → super-admin chat).</li>
 *   <li>{@code VISITOR} — an anonymous kiosk.ke guest chatting with the
 *       platform team (business_id = {@code "platform"}).</li>
 *   <li>{@code STOREFRONT} — an anonymous storefront buyer chatting with the
 *       tenant's staff (business_id = the tenant).</li>
 * </ul>
 *
 * <p>Guests authenticate with a client-held secret token (SHA-256 stored on
 * the conversation); tenant staff and super-admins authenticate via their
 * normal session.</p>
 */
@Service
public class SupportService {

    private static final Logger log = LoggerFactory.getLogger(SupportService.class);

    public static final String GUEST_CHANNEL_PREFIX = "support.guest:";

    private final SupportConversationRepository conversationRepository;
    private final SupportMessageRepository messageRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final GuestSupportTokenService guestTokens;
    private final ApplicationEventPublisher eventPublisher;

    public SupportService(
            SupportConversationRepository conversationRepository,
            SupportMessageRepository messageRepository,
            BusinessRepository businessRepository,
            UserRepository userRepository,
            GuestSupportTokenService guestTokens,
            ApplicationEventPublisher eventPublisher
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.guestTokens = guestTokens;
        this.eventPublisher = eventPublisher;
    }

    public Optional<SupportConversation> findByBusinessId(String businessId) {
        return conversationRepository.findByConversationTypeAndBusinessId(
                SupportConversation.TYPE_TENANT, businessId);
    }

    // ── Tenant side (TENANT thread) ─────────────────────────────────────────

    public SupportConversationDetailDto tenantDetail(String businessId) {
        return findByBusinessId(businessId)
                .map(c -> toDetail(c, businessName(businessId), guestUnread(c)))
                .orElseGet(() -> new SupportConversationDetailDto(null, List.of()));
    }

    public SupportConversationDetailDto createConversation(
            String businessId, String createdByUserId,
            CreateSupportConversationRequest request
    ) {
        SupportConversation conversation = ensureConversation(businessId, createdByUserId,
                resolveUserName(businessId, createdByUserId),
                request == null ? null : request.subject());
        return toDetail(conversation, businessName(businessId), guestUnread(conversation));
    }

    @Transactional
    public SupportMessageDto sendTenantMessage(String businessId, String userId, String body) {
        String userName = resolveUserName(businessId, userId);
        SupportConversation conversation = ensureConversation(businessId, userId, userName, null);
        reopenIfResolved(conversation);
        return persistMessage(conversation, SupportMessage.SENDER_TENANT, userId, userName, body);
    }

    @Transactional
    public boolean markTenantRead(String businessId) {
        return findByBusinessId(businessId).map(this::markGuestRead).orElse(false);
    }

    @Transactional
    public boolean setTenantStatus(String businessId, String status, String userId) {
        SupportConversation conversation = findByBusinessId(businessId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No support conversation yet"));
        return applyStatus(conversation, status, userId);
    }

    public long tenantUnreadCount(String businessId) {
        return findByBusinessId(businessId).map(this::guestUnread).orElse(0L);
    }

    // ── Storefront staff side (STOREFRONT threads) ─────────────────────────

    public List<SupportConversationDto> listStorefrontForTenant(String businessId, String status) {
        List<SupportConversation> rows;
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            rows = conversationRepository.findByConversationTypeAndBusinessIdOrderByLastMessageAtDesc(
                    SupportConversation.TYPE_STOREFRONT, businessId);
        } else {
            rows = conversationRepository.findByConversationTypeAndBusinessIdAndStatusOrderByLastMessageAtDesc(
                    SupportConversation.TYPE_STOREFRONT, businessId, status.toUpperCase());
        }
        return rows.stream().map(c -> toAdminDto(c, businessName(businessId), staffUnread(c))).toList();
    }

    public SupportConversationDetailDto storefrontDetail(String conversationId, String businessId) {
        SupportConversation conversation = requireStorefrontOwned(conversationId, businessId);
        return toDetail(conversation, businessName(businessId), staffUnread(conversation));
    }

    @Transactional
    public SupportMessageDto sendStorefrontStaffMessage(
            String conversationId, String businessId, String userId, String body
    ) {
        SupportConversation conversation = requireStorefrontOwned(conversationId, businessId);
        String userName = resolveUserName(businessId, userId);
        return persistMessage(conversation, SupportMessage.SENDER_TENANT, userId, userName, body);
    }

    @Transactional
    public boolean markStorefrontStaffRead(String conversationId, String businessId) {
        return markStaffRead(requireStorefrontOwned(conversationId, businessId));
    }

    /** Unread buyer messages across all of the tenant's storefront threads. */
    public long storefrontStaffUnreadCount(String businessId) {
        long total = 0;
        for (SupportConversation conversation
                : conversationRepository.findByConversationTypeAndBusinessIdOrderByLastMessageAtDesc(
                        SupportConversation.TYPE_STOREFRONT, businessId)) {
            total += staffUnread(conversation);
        }
        return total;
    }

    // ── Super-admin side ────────────────────────────────────────────────────

    public List<SupportConversationDto> listForAdmin(String status, String type) {
        String conversationType = type == null || type.isBlank()
                ? SupportConversation.TYPE_TENANT
                : type.toUpperCase();
        List<SupportConversation> rows;
        if (status == null || status.isBlank() || "ALL".equalsIgnoreCase(status)) {
            rows = conversationRepository.findAllByConversationTypeOrderByLastMessageAtDesc(conversationType);
        } else {
            rows = conversationRepository.findByStatusAndConversationTypeOrderByLastMessageAtDesc(
                    status.toUpperCase(), conversationType);
        }
        return rows.stream().map(c -> toAdminDto(c, staffUnread(c))).toList();
    }

    public SupportConversationDetailDto adminDetail(String conversationId) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        return toDetail(conversation, displayName(conversation), staffUnread(conversation));
    }

    @Transactional
    public SupportMessageDto sendAdminMessage(
            String conversationId, String adminUserId, String adminName, String body
    ) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        return persistMessage(conversation, SupportMessage.SENDER_SUPER_ADMIN, adminUserId, adminName, body);
    }

    /** Marks the platform's side read and broadcasts read receipts to the tenant/guest. */
    @Transactional
    public boolean markAdminRead(String conversationId) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        return markStaffRead(conversation);
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
            if (SupportConversation.TYPE_STOREFRONT.equals(conversation.getConversationType())) {
                continue; // storefront buyers belong to the tenant's staff inbox
            }
            total += staffUnread(conversation);
        }
        return total;
    }

    public SupportConversation getConversationForTyping(String conversationId) {
        return conversationRepository.findById(conversationId).orElse(null);
    }

    // ── Guest side (VISITOR / STOREFRONT threads) ──────────────────────────

    /**
     * Start or resume a guest thread. When the guest's thread already exists the
     * presented token must match; when it doesn't, a thread is created and a
     * fresh token minted (returned once, then held client-side).
     */
    @Transactional
    public GuestThreadDto guestStartOrResume(
            String type, String businessId, String guestId, String guestName, String body, String presentedToken
    ) {
        requireGuestType(type);
        Optional<SupportConversation> existing =
                conversationRepository.findByConversationTypeAndBusinessIdAndGuestId(type, businessId, guestId);
        if (existing.isPresent()) {
            SupportConversation conversation = existing.get();
            if (!guestTokens.matches(presentedToken, conversation.getGuestTokenHash())) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                        "Guest token missing or no longer valid — please start a fresh conversation");
            }
            if (body != null && !body.isBlank()) {
                reopenIfResolved(conversation);
                persistMessage(conversation, SupportMessage.SENDER_GUEST, guestId, guestName, body);
            }
            return guestPayload(conversation, null);
        }

        SupportConversation conversation = new SupportConversation();
        conversation.setBusinessId(businessId);
        conversation.setConversationType(type);
        conversation.setGuestId(guestId);
        conversation.setGuestName(trimTo(guestName, 120));
        conversation.setStatus(SupportConversation.STATUS_OPEN);
        conversation.setCreatedBy(guestId);
        conversation.setCreatedByName(trimTo(guestName, 191));
        String token = guestTokens.mintToken();
        conversation.setGuestTokenHash(guestTokens.hash(token));
        try {
            conversationRepository.saveAndFlush(conversation);
        } catch (DataIntegrityViolationException ex) {
            // Two tabs raced to create — the unique guest-thread key wins.
            return guestStartOrResume(type, businessId, guestId, guestName, body, presentedToken);
        }
        if (body != null && !body.isBlank()) {
            persistMessage(conversation, SupportMessage.SENDER_GUEST, guestId, guestName, body);
        }
        log.debug("Support guest thread created: type={} business={} guest={}", type, businessId, guestId);
        return guestPayload(conversation, token);
    }

    public GuestThreadDto guestDetail(String conversationId, String guestId, String token) {
        return guestPayload(requireGuestThread(conversationId, guestId, token), null);
    }

    /** Resume an existing guest thread — never creates one (unlike POST /threads). */
    public GuestThreadDto guestResume(String type, String businessId, String guestId, String token) {
        requireGuestType(type);
        SupportConversation conversation = conversationRepository
                .findByConversationTypeAndBusinessIdAndGuestId(type, businessId, guestId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "No conversation yet"));
        if (!guestTokens.matches(token, conversation.getGuestTokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid guest token");
        }
        return guestPayload(conversation, null);
    }

    /** Whether the token opens at least one thread owned by this guest (ticket mint). */
    public boolean guestHasValidToken(String guestId, String token) {
        if (guestId == null || guestId.isBlank()) {
            return false;
        }
        for (SupportConversation conversation : conversationRepository.findByGuestId(guestId)) {
            if (guestTokens.matches(token, conversation.getGuestTokenHash())) {
                return true;
            }
        }
        return false;
    }

    @Transactional
    public SupportMessageDto sendGuestMessage(
            String conversationId, String guestId, String token, String guestName, String body
    ) {
        SupportConversation conversation = requireGuestThread(conversationId, guestId, token);
        reopenIfResolved(conversation);
        return persistMessage(conversation, SupportMessage.SENDER_GUEST, guestId, guestName, body);
    }

    @Transactional
    public boolean markGuestRead(String conversationId, String guestId, String token) {
        return markGuestRead(requireGuestThread(conversationId, guestId, token));
    }

    // ── Internals ───────────────────────────────────────────────────────────

    private SupportConversation requireStorefrontOwned(String conversationId, String businessId) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (!SupportConversation.TYPE_STOREFRONT.equals(conversation.getConversationType())
                || !businessId.equals(conversation.getBusinessId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        return conversation;
    }

    private SupportConversation requireGuestThread(String conversationId, String guestId, String token) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (SupportConversation.TYPE_TENANT.equals(conversation.getConversationType())
                || !guestId.equals(conversation.getGuestId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found");
        }
        if (!guestTokens.matches(token, conversation.getGuestTokenHash())) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid guest token");
        }
        return conversation;
    }

    private void requireGuestType(String type) {
        if (!SupportConversation.TYPE_VISITOR.equals(type)
                && !SupportConversation.TYPE_STOREFRONT.equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Guest conversations must be VISITOR or STOREFRONT");
        }
    }

    private void reopenIfResolved(SupportConversation conversation) {
        if (!SupportConversation.STATUS_OPEN.equals(conversation.getStatus())) {
            conversation.setStatus(SupportConversation.STATUS_OPEN);
            conversationRepository.save(conversation);
            eventPublisher.publishEvent(new SupportEvents.SupportConversationStateEvent(
                    conversation.getBusinessId(), conversation.getId(),
                    SupportConversation.STATUS_OPEN,
                    conversation.getConversationType(), conversation.getGuestId()));
        }
    }

    private SupportMessageDto persistMessage(
            SupportConversation conversation, String senderType,
            String senderUserId, String senderName, String body
    ) {
        SupportMessage message = new SupportMessage();
        message.setConversationId(conversation.getId());
        message.setSenderType(senderType);
        message.setSenderUserId(senderUserId);
        message.setSenderName(senderName);
        message.setBody(body);
        messageRepository.save(message);

        conversation.touchLastMessage(message.getCreatedAt(), previewOf(body));
        touchOwnRead(conversation, senderType);
        conversationRepository.save(conversation);

        eventPublisher.publishEvent(new SupportEvents.SupportMessageSentEvent(
                conversation.getBusinessId(), conversation.getId(), message.getId(),
                senderType, senderUserId, senderName, message.getBody(), message.getCreatedAt(),
                conversation.getConversationType(), conversation.getGuestId()));

        return toMessageDto(message);
    }

    /** The reader's own side always sees their latest message as read. */
    private static void touchOwnRead(SupportConversation conversation, String senderType) {
        Instant now = Instant.now();
        if (SupportMessage.SENDER_TENANT.equals(senderType)) {
            conversation.setTenantLastReadAt(now);
        } else if (SupportMessage.SENDER_SUPER_ADMIN.equals(senderType)) {
            conversation.setAdminLastReadAt(now);
        } else if (SupportMessage.SENDER_GUEST.equals(senderType)) {
            conversation.setGuestLastReadAt(now);
        }
    }

    /** Staff-side read: the super-admin (TENANT/VISITOR) or tenant staff (STOREFRONT). */
    private boolean markStaffRead(SupportConversation conversation) {
        Instant now = Instant.now();
        String staffType = staffType(conversation);
        if (SupportMessage.SENDER_SUPER_ADMIN.equals(staffType)) {
            conversation.setAdminLastReadAt(now);
        } else {
            conversation.setTenantLastReadAt(now);
        }
        conversationRepository.save(conversation);
        int marked = messageRepository.markRead(conversation.getId(), guestType(conversation), now);
        if (marked > 0) {
            eventPublisher.publishEvent(new SupportEvents.SupportMessagesReadEvent(
                    conversation.getBusinessId(), conversation.getId(), staffType,
                    conversation.getConversationType(), conversation.getGuestId()));
        }
        return marked > 0;
    }

    /** Guest-side read: the tenant (TENANT thread) or the anonymous buyer/visitor. */
    private boolean markGuestRead(SupportConversation conversation) {
        Instant now = Instant.now();
        conversation.setGuestLastReadAt(now);
        conversationRepository.save(conversation);
        int marked = messageRepository.markRead(conversation.getId(), staffType(conversation), now);
        if (marked > 0) {
            // The tenant's own thread keeps the classic TENANT receipt contract.
            String readerType = SupportConversation.TYPE_TENANT.equals(conversation.getConversationType())
                    ? SupportMessage.SENDER_TENANT
                    : SupportMessage.SENDER_GUEST;
            eventPublisher.publishEvent(new SupportEvents.SupportMessagesReadEvent(
                    conversation.getBusinessId(), conversation.getId(), readerType,
                    conversation.getConversationType(), conversation.getGuestId()));
        }
        return marked > 0;
    }

    /** Who staffs this thread: SUPER_ADMIN for TENANT/VISITOR, TENANT for STOREFRONT. */
    private static String staffType(SupportConversation conversation) {
        return SupportConversation.TYPE_STOREFRONT.equals(conversation.getConversationType())
                ? SupportMessage.SENDER_TENANT
                : SupportMessage.SENDER_SUPER_ADMIN;
    }

    /** Who the guest side is: the tenant (TENANT thread) or an anonymous GUEST. */
    private static String guestType(SupportConversation conversation) {
        return SupportConversation.TYPE_TENANT.equals(conversation.getConversationType())
                ? SupportMessage.SENDER_TENANT
                : SupportMessage.SENDER_GUEST;
    }

    /** Unread messages from the guest side, per the staff-side read cursor. */
    private long staffUnread(SupportConversation conversation) {
        Instant after = SupportMessage.SENDER_SUPER_ADMIN.equals(staffType(conversation))
                ? (conversation.getAdminLastReadAt() != null ? conversation.getAdminLastReadAt() : Instant.EPOCH)
                : (conversation.getTenantLastReadAt() != null ? conversation.getTenantLastReadAt() : Instant.EPOCH);
        return messageRepository.countByConversationIdAndSenderTypeAndCreatedAtAfter(
                conversation.getId(), guestType(conversation), after);
    }

    /** Unread messages from the staff side, per the guest-side read cursor. */
    private long guestUnread(SupportConversation conversation) {
        Instant after = conversation.getGuestLastReadAt() != null
                ? conversation.getGuestLastReadAt()
                : Instant.EPOCH;
        return messageRepository.countByConversationIdAndSenderTypeAndCreatedAtAfter(
                conversation.getId(), staffType(conversation), after);
    }

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
                conversation.getBusinessId(), conversation.getId(), status,
                conversation.getConversationType(), conversation.getGuestId()));
        log.debug("Support conversation {} → {} by {}", conversation.getId(), status, actorUserId);
        return true;
    }

    private SupportConversation ensureConversation(
            String businessId, String createdByUserId, String createdByName, String subject
    ) {
        return conversationRepository.findByConversationTypeAndBusinessId(
                SupportConversation.TYPE_TENANT, businessId).orElseGet(() -> {
            SupportConversation conversation = new SupportConversation();
            conversation.setBusinessId(businessId);
            conversation.setConversationType(SupportConversation.TYPE_TENANT);
            conversation.setTenantThreadKey(businessId);
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
                // Concurrent create from two devices — the tenant thread key wins.
                return conversationRepository.findByConversationTypeAndBusinessId(
                        SupportConversation.TYPE_TENANT, businessId)
                        .orElseThrow(() -> ex);
            }
        });
    }

    private SupportConversationDetailDto toDetail(
            SupportConversation conversation, String displayName, long unreadCount
    ) {
        List<SupportMessageDto> messages = messageRepository
                .findByConversationIdOrderByCreatedAtAsc(conversation.getId())
                .stream()
                .map(SupportService::toMessageDto)
                .toList();
        return new SupportConversationDetailDto(
                toAdminDto(conversation, displayName, unreadCount),
                messages);
    }

    private GuestThreadDto guestPayload(SupportConversation conversation, String token) {
        SupportConversationDetailDto detail = toDetail(conversation, displayName(conversation), guestUnread(conversation));
        return new GuestThreadDto(detail.conversation(), token, detail.messages());
    }

    private SupportConversationDto toAdminDto(SupportConversation conversation, long unreadCount) {
        return toAdminDto(conversation, displayName(conversation), unreadCount);
    }

    private SupportConversationDto toAdminDto(
            SupportConversation conversation, String displayName, long unreadCount
    ) {
        Business business = businessRepository.findByIdAndDeletedAtIsNull(
                conversation.getBusinessId()).orElse(null);
        String slug = business != null ? business.getSlug() : null;
        return new SupportConversationDto(
                conversation.getId(),
                conversation.getBusinessId(),
                displayName,
                slug,
                conversation.getConversationType(),
                conversation.getGuestId(),
                conversation.getGuestName(),
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

    private String displayName(SupportConversation conversation) {
        if (SupportConversation.TYPE_TENANT.equals(conversation.getConversationType())) {
            return businessName(conversation.getBusinessId());
        }
        return conversation.getGuestName();
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

    private static String trimTo(String value, int max) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) : trimmed;
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
