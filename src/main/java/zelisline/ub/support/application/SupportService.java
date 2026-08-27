package zelisline.ub.support.application;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import zelisline.ub.identity.domain.User;
import zelisline.ub.identity.repository.UserRepository;
import zelisline.ub.storefront.WebOrderCodes;
import zelisline.ub.storefront.domain.WebOrder;
import zelisline.ub.storefront.domain.WebOrderLine;
import zelisline.ub.support.api.dto.CreateSupportConversationRequest;
import zelisline.ub.support.api.dto.GuestThreadDto;
import zelisline.ub.support.api.dto.SendSupportMessageRequest;
import zelisline.ub.support.api.dto.SupportAttachmentDto;
import zelisline.ub.support.api.dto.SupportConversationDetailDto;
import zelisline.ub.support.api.dto.SupportConversationDto;
import zelisline.ub.support.api.dto.SupportMessageDto;
import zelisline.ub.support.api.dto.SupportOrderCardDto;
import zelisline.ub.support.api.dto.SupportWelcomeCardDto;
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

    /**
     * Synthetic sender id for automated platform messages (not a real user row).
     * Column is NOT NULL / CHAR(36) but is not an FK.
     */
    public static final String PLATFORM_BOT_USER_ID = "aaaaaaaa-0000-4000-8000-000000000001";

    private static final long MAX_ATTACHMENT_BYTES = 15L * 1024L * 1024L;
    private static final Set<String> ALLOWED_ATTACHMENT_CONTENT_TYPES = Set.of(
            "image/png",
            "image/jpeg",
            "image/jpg",
            "image/webp",
            "image/gif",
            "application/pdf",
            "text/csv",
            "text/plain",
            "application/csv",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
    );

    private final SupportConversationRepository conversationRepository;
    private final SupportMessageRepository messageRepository;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final GuestSupportTokenService guestTokens;
    private final ApplicationEventPublisher eventPublisher;
    private final ObjectMapper objectMapper;

    public SupportService(
            SupportConversationRepository conversationRepository,
            SupportMessageRepository messageRepository,
            BusinessRepository businessRepository,
            UserRepository userRepository,
            GuestSupportTokenService guestTokens,
            ApplicationEventPublisher eventPublisher,
            ObjectMapper objectMapper
    ) {
        this.conversationRepository = conversationRepository;
        this.messageRepository = messageRepository;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.guestTokens = guestTokens;
        this.eventPublisher = eventPublisher;
        this.objectMapper = objectMapper;
    }

    public Optional<SupportConversation> findByBusinessId(String businessId) {
        return conversationRepository.findByConversationTypeAndBusinessId(
                SupportConversation.TYPE_TENANT, businessId);
    }

    /**
     * Opens the tenant↔platform support thread (if needed) and posts a structured
     * welcome card as a Kiosk message so merchants see it in the chat drawer.
     * Best-effort: never throws to the registration path.
     * Idempotent — skips when a {@code WELCOME_CARD} already exists on the thread.
     *
     * @return {@code true} when a card was posted
     */
    @Transactional
    public boolean postPlatformWelcome(
            String businessId, String createdByUserId, SupportWelcomeCardDto card
    ) {
        if (businessId == null || businessId.isBlank() || card == null) {
            return false;
        }
        try {
            String trimmed = businessId.trim();
            businessRepository.findByIdAndDeletedAtIsNull(trimmed)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
            SupportConversation conversation = ensureConversation(
                    trimmed,
                    createdByUserId != null && !createdByUserId.isBlank()
                            ? createdByUserId.trim()
                            : PLATFORM_BOT_USER_ID,
                    "Kiosk",
                    "Welcome to Kiosk");
            if (messageRepository.countByConversationIdAndMessageKind(
                    conversation.getId(), SupportMessage.KIND_WELCOME_CARD) > 0) {
                return false;
            }
            reopenIfResolved(conversation);

            String payloadJson = objectMapper.writeValueAsString(card);
            String preview = welcomeCardPreview(card);

            SupportMessage message = new SupportMessage();
            message.setConversationId(conversation.getId());
            message.setSenderType(SupportMessage.SENDER_SUPER_ADMIN);
            message.setSenderUserId(PLATFORM_BOT_USER_ID);
            message.setSenderName("Kiosk");
            message.setBody(preview);
            message.setMessageKind(SupportMessage.KIND_WELCOME_CARD);
            message.setPayloadJson(payloadJson);
            messageRepository.save(message);

            conversation.touchLastMessage(message.getCreatedAt(), preview);
            touchOwnRead(conversation, SupportMessage.SENDER_SUPER_ADMIN);
            conversationRepository.save(conversation);

            eventPublisher.publishEvent(new SupportEvents.SupportMessageSentEvent(
                    conversation.getBusinessId(), conversation.getId(), message.getId(),
                    message.getSenderType(), message.getSenderUserId(), message.getSenderName(),
                    message.getBody(),
                    SupportMessage.KIND_WELCOME_CARD,
                    null,
                    card,
                    null,
                    message.getCreatedAt(),
                    conversation.getConversationType(), conversation.getGuestId()));
            return true;
        } catch (Exception ex) {
            log.warn("Failed to post platform welcome into support chat for business {}: {}",
                    businessId, ex.toString());
            return false;
        }
    }

    /**
     * Backfill / repair path: resolve the business + a staff name and post the
     * welcome card when missing. Used by super-admin after older signups.
     *
     * @return {@code true} when a card was posted
     */
    @Transactional
    public boolean ensurePlatformWelcomeForBusiness(String businessId) {
        return ensurePlatformWelcomeForBusiness(businessId, false);
    }

    /**
     * @param replaceExisting when true, remove any prior welcome card and post a fresh one
     */
    @Transactional
    public boolean ensurePlatformWelcomeForBusiness(String businessId, boolean replaceExisting) {
        if (businessId == null || businessId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "businessId is required");
        }
        String trimmed = businessId.trim();
        Business business = businessRepository.findByIdAndDeletedAtIsNull(trimmed)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        User staff = userRepository.findByBusinessIdAndDeletedAtIsNull(trimmed).stream()
                .findFirst()
                .orElse(null);
        String recipientName = staff != null ? staff.getName() : null;
        String createdBy = staff != null ? staff.getId() : PLATFORM_BOT_USER_ID;
        SupportWelcomeCardDto card = new SupportWelcomeCardDto(
                blankToDisplay(recipientName, "there"),
                blankToDisplay(business.getName(), "your business"),
                zelisline.ub.identity.application.WelcomeEmailRenderer.SUPPORT_PHONE,
                zelisline.ub.identity.application.WelcomeEmailRenderer.SUPPORT_EMAIL,
                java.util.List.of(
                        "Getting the online store live",
                        "Themes and custom domains",
                        "M-Pesa on the till",
                        "Products and inventory",
                        "Custom tweaks when something’s missing"));
        if (replaceExisting) {
            SupportConversation conversation = ensureConversation(
                    trimmed, createdBy, "Kiosk", "Welcome to Kiosk");
            messageRepository.deleteByConversationIdAndMessageKind(
                    conversation.getId(), SupportMessage.KIND_WELCOME_CARD);
        }
        return postPlatformWelcome(trimmed, createdBy, card);
    }

    /**
     * Posts a short onboarding tip into the tenant↔platform chat (plain Kiosk message).
     * Best-effort for SA force-send; never throws to the caller path.
     */
    @Transactional
    public boolean postPlatformOnboardingTip(
            String businessId, String title, String body, String ctaPath
    ) {
        if (businessId == null || businessId.isBlank()) {
            return false;
        }
        String tipTitle = title == null ? "" : title.trim();
        String tipBody = body == null ? "" : body.trim();
        if (tipTitle.isEmpty() && tipBody.isEmpty()) {
            return false;
        }
        try {
            String trimmed = businessId.trim();
            businessRepository.findByIdAndDeletedAtIsNull(trimmed)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
            SupportConversation conversation = ensureConversation(
                    trimmed, PLATFORM_BOT_USER_ID, "Kiosk", "Kiosk tips");
            reopenIfResolved(conversation);

            StringBuilder text = new StringBuilder();
            if (!tipTitle.isEmpty()) {
                text.append(tipTitle.trim());
            }
            if (!tipBody.isEmpty()) {
                if (!text.isEmpty()) {
                    text.append("\n\n");
                }
                text.append(tipBody);
            }
            String path = ctaPath == null ? "" : ctaPath.trim();
            if (!path.isEmpty()) {
                text.append("\n\n→ ").append(path.startsWith("/") ? path : "/" + path);
            }
            String messageBody = text.toString().trim();
            if (messageBody.length() > 4000) {
                messageBody = messageBody.substring(0, 3997) + "…";
            }

            persistMessage(
                    conversation,
                    SupportMessage.SENDER_SUPER_ADMIN,
                    PLATFORM_BOT_USER_ID,
                    "Kiosk",
                    messageBody);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to post onboarding tip into support chat for business {}: {}",
                    businessId, ex.toString());
            return false;
        }
    }

    private static String blankToDisplay(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value.trim();
    }

    private static String welcomeCardPreview(SupportWelcomeCardDto card) {
        String business = card.businessName() == null || card.businessName().isBlank()
                ? "your business"
                : card.businessName().trim();
        return "Karibu — " + business + " is on Kiosk. Reply here anytime.";
    }

    /**
     * Posts a structured order card into the storefront buyer↔tenant support thread
     * so staff see the purchase in chat (not only in Web Orders).
     * Best-effort: never throws to the checkout path.
     */
    @Transactional
    public void postStorefrontOrderCard(WebOrder order, List<WebOrderLine> lines, String branchName) {
        if (order == null || order.getBusinessId() == null || order.getBusinessId().isBlank()) {
            return;
        }
        try {
            SupportConversation conversation = ensureStorefrontThreadForOrder(order);
            reopenIfResolved(conversation);

            SupportOrderCardDto card = toOrderCard(order, lines, branchName);
            String payloadJson = objectMapper.writeValueAsString(card);
            String preview = orderCardPreview(card);

            SupportMessage message = new SupportMessage();
            message.setConversationId(conversation.getId());
            message.setSenderType(SupportMessage.SENDER_GUEST);
            message.setSenderUserId(conversation.getGuestId());
            message.setSenderName(trimTo(order.getCustomerName(), 191));
            message.setBody(preview);
            message.setMessageKind(SupportMessage.KIND_ORDER_CARD);
            message.setPayloadJson(payloadJson);
            messageRepository.save(message);

            conversation.touchLastMessage(message.getCreatedAt(), preview);
            // Buyer "sent" it — staff still needs to read; do not mark tenant read.
            conversationRepository.save(conversation);

            eventPublisher.publishEvent(new SupportEvents.SupportMessageSentEvent(
                    conversation.getBusinessId(), conversation.getId(), message.getId(),
                    message.getSenderType(), message.getSenderUserId(), message.getSenderName(),
                    message.getBody(),
                    SupportMessage.KIND_ORDER_CARD,
                    card,
                    null,
                    null,
                    message.getCreatedAt(),
                    conversation.getConversationType(), conversation.getGuestId()));
        } catch (Exception ex) {
            log.warn("Failed to post storefront order {} into support chat: {}",
                    order.getId(), ex.toString());
        }
    }

    private SupportConversation ensureStorefrontThreadForOrder(WebOrder order) {
        String businessId = order.getBusinessId().trim();
        String phone = normalizePhone(order.getCustomerPhone());
        String name = trimTo(order.getCustomerName(), 120);

        SupportConversation conversation = null;
        if (phone != null) {
            conversation = conversationRepository
                    .findByConversationTypeAndBusinessIdAndGuestPhone(
                            SupportConversation.TYPE_STOREFRONT, businessId, phone)
                    .orElse(null);
        }
        if (conversation != null) {
            if (name != null && !name.isBlank()) {
                conversation.setGuestName(name);
                conversation.setCreatedByName(trimTo(name, 191));
            }
            if (phone != null) {
                conversation.setGuestPhone(phone);
            }
            return conversationRepository.save(conversation);
        }

        String guestId = phone != null
                ? ("buyer-phone-" + phone.replaceAll("[^0-9]", ""))
                : ("buyer-order-" + order.getId());
        if (guestId.length() > 64) {
            guestId = guestId.substring(0, 64);
        }

        conversation = conversationRepository
                .findByConversationTypeAndBusinessIdAndGuestId(
                        SupportConversation.TYPE_STOREFRONT, businessId, guestId)
                .orElse(null);
        if (conversation != null) {
            if (phone != null) {
                conversation.setGuestPhone(phone);
            }
            if (name != null) {
                conversation.setGuestName(name);
                conversation.setCreatedByName(trimTo(name, 191));
            }
            return conversationRepository.save(conversation);
        }

        SupportConversation fresh = new SupportConversation();
        fresh.setBusinessId(businessId);
        fresh.setConversationType(SupportConversation.TYPE_STOREFRONT);
        fresh.setGuestId(guestId);
        fresh.setGuestName(name);
        fresh.setGuestPhone(phone);
        fresh.setStatus(SupportConversation.STATUS_OPEN);
        fresh.setCreatedBy(guestId);
        fresh.setCreatedByName(trimTo(name, 191));
        fresh.setSubject("Storefront order");
        String token = guestTokens.mintToken();
        fresh.setGuestTokenHash(guestTokens.hash(token));
        try {
            return conversationRepository.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException ex) {
            // Race — reload by phone/guest id.
            if (phone != null) {
                return conversationRepository
                        .findByConversationTypeAndBusinessIdAndGuestPhone(
                                SupportConversation.TYPE_STOREFRONT, businessId, phone)
                        .orElseThrow(() -> ex);
            }
            return conversationRepository
                    .findByConversationTypeAndBusinessIdAndGuestId(
                            SupportConversation.TYPE_STOREFRONT, businessId, guestId)
                    .orElseThrow(() -> ex);
        }
    }

    private static SupportOrderCardDto toOrderCard(WebOrder order, List<WebOrderLine> lines, String branchName) {
        List<SupportOrderCardDto.Line> cardLines = new ArrayList<>();
        if (lines != null) {
            for (WebOrderLine line : lines) {
                if (line == null) {
                    continue;
                }
                cardLines.add(new SupportOrderCardDto.Line(
                        line.getItemName(),
                        line.getVariantName(),
                        line.getQuantity(),
                        line.getLineTotal()));
            }
        }
        return new SupportOrderCardDto(
                order.getId(),
                WebOrderCodes.code(order.getId()),
                order.getStatus(),
                order.getCurrency(),
                order.getGrandTotal(),
                order.getCustomerName(),
                order.getCustomerPhone(),
                branchName,
                order.getChannel(),
                cardLines,
                cardLines.size());
    }

    private static String orderCardPreview(SupportOrderCardDto card) {
        String code = card.orderCode() == null || card.orderCode().isBlank() ? "order" : card.orderCode();
        String currency = card.currency() == null ? "" : card.currency().trim();
        String total = card.grandTotal() == null ? "" : card.grandTotal().toPlainString();
        String money = (currency + " " + total).trim();
        if (money.isEmpty()) {
            return "🛒 New online order " + code;
        }
        return "🛒 New online order " + code + " · " + money;
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
    public SupportMessageDto sendTenantMessage(
            String businessId, String userId, SendSupportMessageRequest request
    ) {
        String userName = resolveUserName(businessId, userId);
        SupportConversation conversation = ensureConversation(businessId, userId, userName, null);
        reopenIfResolved(conversation);
        return persistMessage(conversation, SupportMessage.SENDER_TENANT, userId, userName, request);
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
            String conversationId, String businessId, String userId, SendSupportMessageRequest request
    ) {
        SupportConversation conversation = requireStorefrontOwned(conversationId, businessId);
        String userName = resolveUserName(businessId, userId);
        reopenIfResolved(conversation);
        return persistMessage(conversation, SupportMessage.SENDER_TENANT, userId, userName, request);
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
        if (SupportConversation.TYPE_STOREFRONT.equals(conversationType)) {
            // Storefront buyer threads belong to the tenant staff inbox only.
            return List.of();
        }
        if (!SupportConversation.TYPE_TENANT.equals(conversationType)
                && !SupportConversation.TYPE_VISITOR.equals(conversationType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "type must be TENANT or VISITOR");
        }
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
        SupportConversation conversation = requirePlatformStaffed(conversationId);
        return toDetail(conversation, displayName(conversation), staffUnread(conversation));
    }

    /**
     * Opens (or creates) the platform TENANT thread for a business so a super-admin can
     * message the shop without waiting for the tenant to start the chat first.
     */
    @Transactional
    public SupportConversationDetailDto ensureTenantThreadForAdmin(
            String businessId, String adminUserId, String adminName
    ) {
        if (businessId == null || businessId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "businessId is required");
        }
        String trimmed = businessId.trim();
        businessRepository.findByIdAndDeletedAtIsNull(trimmed)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Business not found"));
        String actorName = adminName == null || adminName.isBlank() ? "Kiosk Support" : adminName.trim();
        SupportConversation conversation = ensureConversation(
                trimmed, adminUserId, actorName, "Started by Kiosk Support");
        reopenIfResolved(conversation);
        return toDetail(conversation, displayName(conversation), staffUnread(conversation));
    }

    @Transactional
    public SupportMessageDto sendAdminMessage(
            String conversationId, String adminUserId, String adminName, SendSupportMessageRequest request
    ) {
        SupportConversation conversation = requirePlatformStaffed(conversationId);
        reopenIfResolved(conversation);
        return persistMessage(conversation, SupportMessage.SENDER_SUPER_ADMIN, adminUserId, adminName, request);
    }

    /** Marks the platform's side read and broadcasts read receipts to the tenant/guest. */
    @Transactional
    public boolean markAdminRead(String conversationId) {
        return markStaffRead(requirePlatformStaffed(conversationId));
    }

    @Transactional
    public boolean setAdminStatus(String conversationId, String status) {
        return applyStatus(requirePlatformStaffed(conversationId), status, null);
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
     * Start or resume a guest thread. The phone is the identity anchor:
     * <ul>
     *   <li>phone matches an existing thread → the visitor resumes it (one
     *       continuous conversation per person, even from a new browser);</li>
     *   <li>otherwise a thread for this guestId+token resumes;</li>
     *   <li>otherwise a new thread is created and a fresh token minted.</li>
     * </ul>
     * A different device claiming an existing phone thread adopts it and
     * rotates the secret, so the previous device must re-identify itself.
     */
    @Transactional
    public GuestThreadDto guestStartOrResume(
            String type, String businessId, String guestId, String guestName,
            String guestPhone, String body, String presentedToken
    ) {
        requireGuestType(type);
        String phone = normalizePhone(guestPhone);

        SupportConversation conversation = null;
        boolean foundByPhone = false;
        String adoptedToken = null;

        if (phone != null) {
            conversation = conversationRepository
                    .findByConversationTypeAndBusinessIdAndGuestPhone(type, businessId, phone)
                    .orElse(null);
            foundByPhone = conversation != null;
        }
        if (conversation == null) {
            conversation = conversationRepository
                    .findByConversationTypeAndBusinessIdAndGuestId(type, businessId, guestId)
                    .orElse(null);
        }

        if (conversation != null) {
            if (foundByPhone) {
                // The phone is the credential: a returning visitor (or a device
                // with a rotated-out secret) always gets back in; the secret
                // rotates so only the active device holds a working token.
                adoptedToken = adoptOrRefresh(conversation, guestId, guestName, phone, presentedToken);
            } else if (guestId.equals(conversation.getGuestId())) {
                if (guestTokens.matches(presentedToken, conversation.getGuestTokenHash())) {
                    refreshGuestIdentity(conversation, guestName, phone);
                } else if (phone != null) {
                    // Stale or lost token, but the visitor presents their phone:
                    // attach it and hand back a fresh secret (recovers pre-phone
                    // threads and rotated-out devices without fragmenting).
                    adoptedToken = adoptConversation(conversation, guestId, guestName, phone);
                } else {
                    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,
                            "Guest token missing or no longer valid — please start a fresh conversation");
                }
            } else {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No conversation yet");
            }
            if (body != null && !body.isBlank()) {
                reopenIfResolved(conversation);
                persistMessage(conversation, SupportMessage.SENDER_GUEST, guestId, guestName, body);
            }
            return guestPayload(conversation, adoptedToken);
        }

        SupportConversation fresh = new SupportConversation();
        fresh.setBusinessId(businessId);
        fresh.setConversationType(type);
        fresh.setGuestId(guestId);
        fresh.setGuestName(trimTo(guestName, 120));
        fresh.setGuestPhone(phone);
        fresh.setStatus(SupportConversation.STATUS_OPEN);
        fresh.setCreatedBy(guestId);
        fresh.setCreatedByName(trimTo(guestName, 191));
        String token = guestTokens.mintToken();
        fresh.setGuestTokenHash(guestTokens.hash(token));
        try {
            conversationRepository.saveAndFlush(fresh);
        } catch (DataIntegrityViolationException ex) {
            // Two tabs raced to create — the unique guest-thread key wins.
            return guestStartOrResume(type, businessId, guestId, guestName, guestPhone, body, presentedToken);
        }
        if (body != null && !body.isBlank()) {
            persistMessage(fresh, SupportMessage.SENDER_GUEST, guestId, guestName, body);
        }
        log.debug("Support guest thread created: type={} business={} guest={}", type, businessId, guestId);
        return guestPayload(fresh, token);
    }

    public GuestThreadDto guestDetail(String conversationId, String guestId, String token) {
        return guestPayload(requireGuestThread(conversationId, guestId, token), null);
    }

    /** Resume an existing guest thread — never creates one (unlike POST /threads). */
    public GuestThreadDto guestResume(
            String type, String businessId, String guestId, String guestPhone, String token
    ) {
        requireGuestType(type);
        String phone = normalizePhone(guestPhone);

        SupportConversation conversation = null;
        boolean foundByPhone = false;
        if (phone != null) {
            conversation = conversationRepository
                    .findByConversationTypeAndBusinessIdAndGuestPhone(type, businessId, phone)
                    .orElse(null);
            foundByPhone = conversation != null;
        }
        if (conversation == null) {
            conversation = conversationRepository
                    .findByConversationTypeAndBusinessIdAndGuestId(type, businessId, guestId)
                    .orElse(null);
        }
        if (conversation == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No conversation yet");
        }

        if (foundByPhone) {
            // Phone re-validates the visitor even when their token was rotated
            // out by another device — hand back a fresh secret.
            return guestPayload(conversation,
                    adoptOrRefresh(conversation, guestId, null, phone, token));
        }
        if (!guestId.equals(conversation.getGuestId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "No conversation yet");
        }
        if (guestTokens.matches(token, conversation.getGuestTokenHash())) {
            refreshGuestIdentity(conversation, null, phone);
            return guestPayload(conversation, null);
        }
        if (phone != null) {
            // Same device, rotated-out secret — the phone re-identifies and
            // the server hands back a fresh token.
            return guestPayload(conversation, adoptConversation(conversation, guestId, null, phone));
        }
        throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid guest token");
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
            String conversationId, String guestId, String token, SendSupportMessageRequest request
    ) {
        SupportConversation conversation = requireGuestThread(conversationId, guestId, token);
        reopenIfResolved(conversation);
        String guestName = request.guestName();
        return persistMessage(conversation, SupportMessage.SENDER_GUEST, guestId, guestName, request);
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

    /**
     * Super-admin only staffs TENANT and VISITOR threads. Storefront buyer chats
     * are invisible here — they belong to the tenant's own inbox.
     */
    private SupportConversation requirePlatformStaffed(String conversationId) {
        SupportConversation conversation = conversationRepository.findById(conversationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Conversation not found"));
        if (SupportConversation.TYPE_STOREFRONT.equals(conversation.getConversationType())) {
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

    /** Validates guest ownership without sending a message (e.g. Cloudinary signature). */
    public void requireGuestThreadAccess(String conversationId, String guestId, String token) {
        requireGuestThread(conversationId, guestId, token);
    }

    private void requireGuestType(String type) {
        if (!SupportConversation.TYPE_VISITOR.equals(type)
                && !SupportConversation.TYPE_STOREFRONT.equals(type)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "Guest conversations must be VISITOR or STOREFRONT");
        }
    }

    /** Keep the visitor's latest name/phone on the thread they resumed. */
    private void refreshGuestIdentity(SupportConversation conversation, String guestName, String phone) {
        boolean changed = false;
        if (phone != null && !phone.equals(conversation.getGuestPhone())) {
            conversation.setGuestPhone(phone);
            changed = true;
        }
        if (guestName != null && !guestName.isBlank()
                && !guestName.trim().equals(conversation.getGuestName())) {
            conversation.setGuestName(trimTo(guestName, 120));
            changed = true;
        }
        if (changed) {
            conversationRepository.save(conversation);
        }
    }

    /**
     * Resolve a thread that was found via the visitor's phone. A valid token on
     * the owning device is a no-op; anything else (new device, rotated-out
     * token) re-claims the thread and rotates the secret.
     */
    private String adoptOrRefresh(
            SupportConversation conversation, String guestId,
            String guestName, String phone, String presentedToken
    ) {
        if (guestId.equals(conversation.getGuestId())
                && guestTokens.matches(presentedToken, conversation.getGuestTokenHash())) {
            refreshGuestIdentity(conversation, guestName, phone);
            return null; // same device, token still valid — nothing to rotate
        }
        return adoptConversation(conversation, guestId, guestName, phone);
    }

    /** A new device claims this thread via its phone — adopt it and rotate the secret. */
    private String adoptConversation(SupportConversation conversation, String guestId, String guestName, String phone) {
        conversation.setGuestId(guestId);
        if (guestName != null && !guestName.isBlank()) {
            conversation.setGuestName(trimTo(guestName, 120));
        }
        if (phone != null && !phone.equals(conversation.getGuestPhone())) {
            conversation.setGuestPhone(phone);
        }
        String token = guestTokens.mintToken();
        conversation.setGuestTokenHash(guestTokens.hash(token));
        conversationRepository.save(conversation);
        log.debug("Support guest thread adopted by new device: conversation={} guest={}",
                conversation.getId(), guestId);
        return token;
    }

    private static String normalizePhone(String phone) {
        if (phone == null) {
            return null;
        }
        String trimmed = phone.trim();
        if (trimmed.isBlank()) {
            return null;
        }
        String digits = trimmed.replaceAll("[^0-9+]", "");
        return digits.length() >= 9 && digits.length() <= 16 ? digits : null;
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
        return persistMessage(conversation, senderType, senderUserId, senderName,
                new SendSupportMessageRequest(body, null, null));
    }

    private SupportMessageDto persistMessage(
            SupportConversation conversation, String senderType,
            String senderUserId, String senderName, SendSupportMessageRequest request
    ) {
        SupportAttachmentDto attachment = normalizeAttachment(request == null ? null : request.attachment());
        String body = normalizeBody(request == null ? null : request.body(), attachment);

        SupportMessage message = new SupportMessage();
        message.setConversationId(conversation.getId());
        message.setSenderType(senderType);
        message.setSenderUserId(senderUserId);
        message.setSenderName(senderName);
        message.setBody(body);
        if (attachment != null) {
            message.setAttachmentUrl(attachment.url());
            message.setAttachmentPublicId(blankToNull(attachment.publicId()));
            message.setAttachmentFileName(blankToNull(attachment.fileName()));
            message.setAttachmentContentType(blankToNull(attachment.contentType()));
            message.setAttachmentBytes(attachment.bytes());
        }
        messageRepository.save(message);

        conversation.touchLastMessage(message.getCreatedAt(), previewOf(body, attachment));
        touchOwnRead(conversation, senderType);
        conversationRepository.save(conversation);

        eventPublisher.publishEvent(new SupportEvents.SupportMessageSentEvent(
                conversation.getBusinessId(), conversation.getId(), message.getId(),
                senderType, senderUserId, senderName, message.getBody(),
                SupportMessage.KIND_TEXT,
                null,
                null,
                toAttachmentDto(message),
                message.getCreatedAt(),
                conversation.getConversationType(), conversation.getGuestId()));

        return toMessageDto(message);
    }

    private static String normalizeBody(String raw, SupportAttachmentDto attachment) {
        String body = raw == null ? "" : raw.trim();
        if (body.isEmpty() && attachment == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message body or attachment is required");
        }
        if (body.length() > 4000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Message body is too long");
        }
        return body;
    }

    private static SupportAttachmentDto normalizeAttachment(SupportAttachmentDto raw) {
        if (raw == null) {
            return null;
        }
        String url = raw.url() == null ? "" : raw.url().trim();
        if (url.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attachment url is required");
        }
        if (!isAllowedCloudinaryUrl(url)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attachment must be a Cloudinary HTTPS URL");
        }
        if (url.length() > 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attachment url is too long");
        }
        Long bytes = raw.bytes();
        if (bytes != null && (bytes < 0 || bytes > MAX_ATTACHMENT_BYTES)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attachment is too large");
        }
        String contentType = blankToNull(raw.contentType());
        if (contentType != null && !isAllowedAttachmentContentType(contentType)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Attachment type is not allowed");
        }
        return new SupportAttachmentDto(
                url,
                blankToNull(raw.publicId()),
                blankToNull(raw.fileName()),
                contentType,
                bytes
        );
    }

    private static boolean isAllowedCloudinaryUrl(String url) {
        String lower = url.toLowerCase();
        return lower.startsWith("https://res.cloudinary.com/")
                || (lower.startsWith("https://") && lower.contains(".cloudinary.com/"));
    }

    private static boolean isAllowedAttachmentContentType(String contentType) {
        String ct = contentType.trim().toLowerCase();
        int sc = ct.indexOf(';');
        if (sc > 0) {
            ct = ct.substring(0, sc).trim();
        }
        return ALLOWED_ATTACHMENT_CONTENT_TYPES.contains(ct);
    }

    private static String blankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
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
                .map(this::toMessageDto)
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
                conversation.getGuestPhone(),
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

    private SupportMessageDto toMessageDto(SupportMessage message) {
        return new SupportMessageDto(
                message.getId(),
                message.getConversationId(),
                message.getSenderType(),
                message.getSenderUserId(),
                message.getSenderName(),
                message.getBody(),
                message.getMessageKind() == null || message.getMessageKind().isBlank()
                        ? SupportMessage.KIND_TEXT
                        : message.getMessageKind(),
                parseOrderCard(message),
                parseWelcomeCard(message),
                toAttachmentDto(message),
                message.getReadAt(),
                message.getCreatedAt()
        );
    }

    private SupportOrderCardDto parseOrderCard(SupportMessage message) {
        if (message.getPayloadJson() == null || message.getPayloadJson().isBlank()) {
            return null;
        }
        if (!SupportMessage.KIND_ORDER_CARD.equals(message.getMessageKind())) {
            return null;
        }
        try {
            return objectMapper.readValue(message.getPayloadJson(), SupportOrderCardDto.class);
        } catch (Exception ex) {
            log.debug("Could not parse order card payload for message {}: {}", message.getId(), ex.toString());
            return null;
        }
    }

    private SupportWelcomeCardDto parseWelcomeCard(SupportMessage message) {
        if (message.getPayloadJson() == null || message.getPayloadJson().isBlank()) {
            return null;
        }
        if (!SupportMessage.KIND_WELCOME_CARD.equals(message.getMessageKind())) {
            return null;
        }
        try {
            return objectMapper.readValue(message.getPayloadJson(), SupportWelcomeCardDto.class);
        } catch (Exception ex) {
            log.debug("Could not parse welcome card payload for message {}: {}", message.getId(), ex.toString());
            return null;
        }
    }

    private static SupportAttachmentDto toAttachmentDto(SupportMessage message) {
        if (message.getAttachmentUrl() == null || message.getAttachmentUrl().isBlank()) {
            return null;
        }
        return new SupportAttachmentDto(
                message.getAttachmentUrl(),
                message.getAttachmentPublicId(),
                message.getAttachmentFileName(),
                message.getAttachmentContentType(),
                message.getAttachmentBytes()
        );
    }

    private static String previewOf(String body, SupportAttachmentDto attachment) {
        String flat = body == null ? "" : body.replaceAll("\\s+", " ").trim();
        if (!flat.isEmpty()) {
            return flat.length() > 140 ? flat.substring(0, 140) + "…" : flat;
        }
        if (attachment != null) {
            String name = attachment.fileName();
            if (name != null && !name.isBlank()) {
                return "📎 " + name.trim();
            }
            return "📎 Attachment";
        }
        return null;
    }
}
